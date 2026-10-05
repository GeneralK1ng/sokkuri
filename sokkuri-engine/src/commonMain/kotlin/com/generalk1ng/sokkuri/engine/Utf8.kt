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
 * UTF-8 blob primitives: code-point-ordered access to dictionary text that
 * lives in a raw byte buffer (the `.sok` format's key/value blobs), without
 * materializing decoded strings — the m3-string-view.md §3.2 optimization
 * layer. Every function here has a defined equivalence against the UTF-16
 * reference implementation in [Utf] (stated per function; pinned by
 * `Utf8Test`), so retrieval over byte storage cannot drift from retrieval
 * over string storage (invariant I2 by construction, not by review).
 *
 * Trust boundary (m1-dictgen-sok §3.2, same as the `.sok` decoder):
 * dictionary blobs are encoder-produced and always valid UTF-8; validity is
 * deliberately not re-validated per access. Malformed input nonetheless
 * stays total — the sequence decodes as U+FFFD advancing exactly one byte —
 * so a corrupt file degrades to replacement characters instead of crashing
 * the engine, never worse than today's `decodeToString` path.
 */
@SokkuriInternalApi
public object Utf8 {

    /** Code point produced for malformed input; matches [Utf] leniency in spirit. */
    public const val REPLACEMENT_CODE_POINT: Int = 0xFFFD

    /**
     * Decoded (code point, width) pair packed into a Long: high 32 bits the
     * code point, low 32 bits the sequence width in bytes. A private
     * transport so validation rules live in exactly one place.
     */
    private fun pack(codePoint: Int, width: Int): Long =
        (codePoint.toLong() shl 32) or width.toLong()

    private fun decodedCodePoint(packed: Long): Int = (packed ushr 32).toInt()

    private fun decodedWidth(packed: Long): Int = packed.toInt()

    /** U+FFFD semantics for malformed input: replacement, one byte consumed. */
    private fun malformed(): Long = pack(REPLACEMENT_CODE_POINT, 1)

    private fun isContinuation(byte: Byte): Boolean = (byte.toInt() and 0xC0) == 0x80

    private fun continuationBits(value: Int): Int = value and 0x3F

    /**
     * Decodes the UTF-8 sequence starting at [index] (must be `< limit`).
     * Full validation, one decision point per rule: overlong forms, UTF-16
     * surrogate range, and values beyond U+10FFFF are malformed; a valid
     * lead byte with truncated continuations is malformed as a whole.
     */
    private fun decode(bytes: ByteArray, index: Int, limit: Int): Long {
        val b0 = bytes[index].toInt() and 0xFF
        if (b0 < 0x80) return pack(b0, 1)
        return when (b0) {
            in 0xC2..0xDF -> {
                if (index + 1 < limit && isContinuation(bytes[index + 1])) {
                    pack(((b0 and 0x1F) shl 6) or continuationBits(bytes[index + 1].toInt()), 2)
                } else {
                    malformed()
                }
            }

            in 0xE0..0xEF -> {
                if (index + 2 < limit) {
                    val b1 = bytes[index + 1].toInt() and 0xFF
                    val b2 = bytes[index + 2].toInt() and 0xFF
                    // E0: exclude overlong (b1 < 0xA0); ED: exclude surrogates.
                    val b1Valid = when (b0) {
                        0xE0 -> b1 in 0xA0..0xBF
                        0xED -> b1 in 0x80..0x9F
                        else -> b1 in 0x80..0xBF
                    }
                    if (b1Valid && (b2 and 0xC0) == 0x80) {
                        pack(
                            ((b0 and 0x0F) shl 12) or
                                    (continuationBits(b1) shl 6) or continuationBits(b2),
                            3,
                        )
                    } else {
                        malformed()
                    }
                } else {
                    malformed()
                }
            }

            in 0xF0..0xF4 -> {
                if (index + 3 < limit) {
                    val b1 = bytes[index + 1].toInt() and 0xFF
                    val b2 = bytes[index + 2].toInt() and 0xFF
                    val b3 = bytes[index + 3].toInt() and 0xFF
                    // F0: exclude overlong (b1 < 0x90); F4: cap at U+10FFFF.
                    val b1Valid = when (b0) {
                        0xF0 -> b1 in 0x90..0xBF
                        0xF4 -> b1 in 0x80..0x8F
                        else -> b1 in 0x80..0xBF
                    }
                    if (b1Valid && (b2 and 0xC0) == 0x80 && (b3 and 0xC0) == 0x80) {
                        pack(
                            ((b0 and 0x07) shl 18) or
                                    (continuationBits(b1) shl 12) or
                                    (continuationBits(b2) shl 6) or continuationBits(b3),
                            4,
                        )
                    } else {
                        malformed()
                    }
                } else {
                    malformed()
                }
            }

            else -> malformed() // 0x80..0xC1 (continuation/overlong lead), 0xF5..0xFF
        }
    }

    /**
     * First code point of the UTF-8 region `[offset, offset + length)`.
     * Equivalence: `Utf.codePointAt(region.decodeToString(), 0)` for valid
     * UTF-8; an empty region yields [REPLACEMENT_CODE_POINT] (keys are
     * non-empty by format invariant; the guard only keeps the call total).
     */
    public fun firstCodePointAt(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return REPLACEMENT_CODE_POINT
        return decodedCodePoint(decode(bytes, offset, offset + length))
    }

    /**
     * Code-point-ordered comparison of the UTF-8 region against the
     * `text[start, end)` window, same order as [Utf.compareByCodePoint] —
     * the order `.sok` blobs are sorted with, so binary search over byte
     * storage shares the comparator binary search over string storage uses
     * (invariant I2). Equivalence for valid UTF-8:
     * `sign(compareRegionToWindow(...)) == sign(Utf.compareByCodePoint(regionString, windowString))`,
     * including every exhaustion case (a region that is a strict prefix of
     * the window compares < 0, and vice versa).
     */
    public fun compareRegionToWindow(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        text: CharArray,
        start: Int,
        end: Int,
    ): Int {
        val limit = offset + length
        var i = offset
        var j = start
        while (i < limit && j < end) {
            val windowCodePoint = Utf.codePointAt(text, j, end)
            val packed = decode(bytes, i, limit)
            val regionCodePoint = decodedCodePoint(packed)
            if (regionCodePoint != windowCodePoint) return regionCodePoint - windowCodePoint
            i += decodedWidth(packed)
            j += Utf.charCount(windowCodePoint)
        }
        // Exhaustion: the reference comparator's tail is the code-unit
        // remainder difference; keep the sign identical.
        return utf16LengthOf(bytes, i, limit - i) - (end - j)
    }

    /**
     * Whether the UTF-8 region equals the start of the `text[start, end)`
     * window, code unit by decoded code unit — the byte-storage form of
     * [Utf.startsWithAt]. An empty region is a prefix of everything.
     */
    public fun startsWithRegionAt(
        bytes: ByteArray,
        offset: Int,
        length: Int,
        text: CharArray,
        start: Int,
        end: Int,
    ): Boolean {
        val limit = offset + length
        var i = offset
        var j = start
        while (i < limit) {
            if (j >= end) return false
            val windowCodePoint = Utf.codePointAt(text, j, end)
            val packed = decode(bytes, i, limit)
            if (decodedCodePoint(packed) != windowCodePoint) return false
            i += decodedWidth(packed)
            j += Utf.charCount(windowCodePoint)
        }
        return true
    }

    /**
     * Appends the region's decoding to [out], surrogate pairs emitted as
     * two appended chars. Equivalence for valid UTF-8: the chars appended
     * equal `region.decodeToString()` exactly.
     */
    public fun decodeAppend(bytes: ByteArray, offset: Int, length: Int, out: StringBuilder) {
        val limit = offset + length
        var i = offset
        while (i < limit) {
            val packed = decode(bytes, i, limit)
            i += decodedWidth(packed)
            val codePoint = decodedCodePoint(packed)
            if (codePoint < 0x10000) {
                out.append(codePoint.toChar())
            } else {
                val value = codePoint - 0x10000
                out.append((0xD800 + (value ushr 10)).toChar())
                out.append((0xDC00 + (value and 0x3FF)).toChar())
            }
        }
    }

    /**
     * UTF-16 code-unit length of the region (invariant I1 accounting):
     * supplementary code points count 2. Equivalence for valid UTF-8:
     * `utf16LengthOf(...) == region.decodeToString().length`.
     */
    public fun utf16LengthOf(bytes: ByteArray, offset: Int, length: Int): Int {
        val limit = offset + length
        var i = offset
        var units = 0
        while (i < limit) {
            val packed = decode(bytes, i, limit)
            units += Utf.charCount(decodedCodePoint(packed))
            i += decodedWidth(packed)
        }
        return units
    }
}
