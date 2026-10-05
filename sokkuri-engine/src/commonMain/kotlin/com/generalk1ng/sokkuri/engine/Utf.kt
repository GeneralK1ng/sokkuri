/*
 * Copyright 2026 The Sokkuri Authors and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.SokkuriInternalApi

/**
 * Unicode/UTF-16 primitives for the conversion engine.
 *
 * This is the port of OpenCC's `UTF8Util` IDS-handling surface, adapted to
 * Kotlin's UTF-16 text model: engine positions are always UTF-16 code-unit
 * indices into a `CharArray`, and all "length" values are code-unit counts.
 * Code points drive dictionary ordering and IDS semantics.
 */
@SokkuriInternalApi
public object Utf {

    /** Maximum nesting depth of an ideographic description sequence. */
    private const val MAX_IDS_DEPTH: Int = 16

    /** Maximum number of code points in an ideographic description sequence. */
    private const val MAX_IDS_CODE_POINTS: Int = 64

    /** First ideographic description operator code point (U+2FF0 ⿰). */
    public const val FIRST_IDS_OPERATOR: Int = 0x2FF0

    /** Last ideographic description operator code point (U+2FFF ⿿). */
    public const val LAST_IDS_OPERATOR: Int = 0x2FFF

    /**
     * Returns the code point at [index] in [chars], reading at most
     * [limit] - [index] code units. Unpaired surrogates are returned as-is
     * (they act as their own "code point"), matching lenient traversal.
     */
    public fun codePointAt(chars: CharArray, index: Int, limit: Int = chars.size): Int {
        val first = chars[index]
        if (first.isHighSurrogate() && index + 1 < limit) {
            val second = chars[index + 1]
            if (second.isLowSurrogate()) {
                return ((first.code - 0xD800) shl 10) + (second.code - 0xDC00) + 0x10000
            }
        }
        return first.code
    }

    /**
     * [String] overload of [codePointAt] for dictionary keys, which are
     * immutable strings rather than engine text buffers.
     */
    public fun codePointAt(text: String, index: Int, limit: Int = text.length): Int {
        val first = text[index]
        if (first.isHighSurrogate() && index + 1 < limit) {
            val second = text[index + 1]
            if (second.isLowSurrogate()) {
                return ((first.code - 0xD800) shl 10) + (second.code - 0xDC00) + 0x10000
            }
        }
        return first.code
    }

    /** Number of UTF-16 code units used to encode [codePoint]. */
    public fun charCount(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1

    /** Port of `IdeographicDescriptionOperatorArity` (OpenCC `UTF8Util`). */
    public fun ideographicDescriptionOperatorArity(codePoint: Int): Int {
        return when (codePoint) {
            0x2FF2, 0x2FF3 -> 3
            0x2FFE, 0x2FFF -> 1
            0x2FF0, 0x2FF1,
            0x2FF4, 0x2FF5, 0x2FF6, 0x2FF7, 0x2FF8, 0x2FF9,
            0x2FFA, 0x2FFB, 0x2FFC, 0x2FFD,
            -> 2
            else -> 0
        }
    }

    /**
     * Port of `NextIdeographicDescriptionSequenceLength`.
     *
     * Returns the length in UTF-16 code units of the complete ideographic
     * description sequence starting at [start], or `0` when the text there
     * is not a complete sequence (single characters, operators with missing
     * operands, over-depth or over-length trees). Callers treat `0` as
     * "consume a single character", exactly like OpenCC.
     */
    public fun ideographicDescriptionSequenceLength(
        chars: CharArray,
        start: Int,
        limit: Int = chars.size,
    ): Int {
        if (start >= limit) return 0
        val codePoint = codePointAt(chars, start, limit)
        if (ideographicDescriptionOperatorArity(codePoint) == 0) return 0

        val consumed = IntArray(1)
        val codePoints = IntArray(1)
        return when (
            consumeIdeographicDescriptionSequence(
                chars, start, limit, MAX_IDS_DEPTH, MAX_IDS_CODE_POINTS, consumed, codePoints,
            )
        ) {
            IdsParseStatus.COMPLETE -> consumed[0]
            IdsParseStatus.INCOMPLETE, IdsParseStatus.INVALID -> 0
        }
    }

    /**
     * Port of `ConsumeIdeographicDescriptionSequence`: an operator consumes
     * exactly [arity] complete operand sequences; the whole tree must fit
     * the depth and code-point budgets.
     */
    private fun consumeIdeographicDescriptionSequence(
        chars: CharArray,
        start: Int,
        limit: Int,
        depthLeft: Int,
        maxCodePoints: Int,
        consumed: IntArray,
        codePoints: IntArray,
    ): IdsParseStatus {
        if (start >= limit) return IdsParseStatus.INCOMPLETE
        if (depthLeft == 0 || codePoints[0] >= maxCodePoints) return IdsParseStatus.INVALID

        val codePoint = codePointAt(chars, start, limit)
        val firstWidth = charCount(codePoint)
        if (start + firstWidth > limit) return IdsParseStatus.INCOMPLETE
        codePoints[0] += 1

        val arity = ideographicDescriptionOperatorArity(codePoint)
        if (arity == 0) {
            consumed[0] = firstWidth
            return IdsParseStatus.COMPLETE
        }

        var offset = firstWidth
        var i = 0
        while (i < arity) {
            if (start + offset >= limit) return IdsParseStatus.INCOMPLETE
            val operandLength = IntArray(1)
            val operandStatus = consumeIdeographicDescriptionSequence(
                chars, start + offset, limit, depthLeft - 1, maxCodePoints,
                operandLength, codePoints,
            )
            if (operandStatus != IdsParseStatus.COMPLETE) return operandStatus
            offset += operandLength[0]
            i += 1
        }
        consumed[0] = offset
        return IdsParseStatus.COMPLETE
    }

    private enum class IdsParseStatus { COMPLETE, INCOMPLETE, INVALID }

    /**
     * Compares two strings by code-point order (the ordering OpenCC uses for
     * its sorted dictionaries). Differs from UTF-16 [String.compareTo] only
     * when non-BMP characters are involved: with surrogate pairs, UTF-16
     * order interleaves supplementary planes differently than code-point
     * order. Sorting and binary search must share this comparator.
     */
    public fun compareByCodePoint(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = codePointAt(a, i)
            val cb = codePointAt(b, j)
            if (ca != cb) return ca - cb
            i += charCount(ca)
            j += charCount(cb)
        }
        return (a.length - i) - (b.length - j)
    }

    /**
     * Compares [text] against the `chars[start, end)` window by code-point
     * order — the mixed String/CharArray form of [compareByCodePoint], added
     * for storage-agnostic key tables (m3-string-view.md §3.3) so the String
     * backend of [SortedTableRetrieval] compares without materializing the
     * window. Semantics: identical to
     * `compareByCodePoint(text, window-as-String)`, sign included.
     */
    public fun compareByCodePoint(text: String, chars: CharArray, start: Int, end: Int): Int {
        var i = 0
        var j = start
        while (i < text.length && j < end) {
            val ct = codePointAt(text, i)
            val cw = codePointAt(chars, j, end)
            if (ct != cw) return ct - cw
            i += charCount(ct)
            j += charCount(cw)
        }
        return (text.length - i) - (end - j)
    }

    /** Whether [text] starts with [prefix] at [start] (code-unit comparison). */
    public fun startsWithAt(text: CharArray, start: Int, end: Int, prefix: String): Boolean {
        if (end - start < prefix.length) return false
        var i = 0
        while (i < prefix.length) {
            if (text[start + i] != prefix[i]) return false
            i += 1
        }
        return true
    }
}
