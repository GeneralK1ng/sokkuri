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
 * The miss branch both scan loops share ([Conversion] and
 * [MaxMatchSegmentation]), the port of the unmatched case of OpenCC's
 * `Conversion::AppendConverted` and `SegmentText`: the ideographic
 * description sequence or code point at the cursor is consumed whole, then
 * `PrefixMatch::SkipUnmatchable` extends the run over every character that
 * cannot begin a key, stopping at each IDS operator with non-zero arity
 * ([stopsBulkSkip]).
 *
 * The stage takes the dictionary's [Dictionary.keyStarts] once, when it is
 * built, as upstream builds its `Utf8SkipTable` once per `PrefixMatch`; the
 * skip then costs one bitmap test and one range comparison per character and
 * no call into the dictionary. The set is referenced, not copied: a `.sok`
 * dictionary's set is shared by every converter in the process, and a group
 * builds its union once for the stage. A dictionary that cannot enumerate its
 * key starts is asked through [stopsBulkSkip] instead, with the same result.
 *
 * Immutable and safe for concurrent use, like the stage that owns it.
 */
@OptIn(SokkuriInternalApi::class)
internal class BulkSkip(private val dict: Dictionary) {

    /** The code points that begin a key of [dict], or `null` when it cannot enumerate them. */
    private val keyStarts: CodePointSet? = dict.keyStarts()

    /**
     * End of the unmatched run that begins at [start], which must be `< end`
     * and a position no key of the dictionary prefixes: past the IDS (or the
     * code point) at [start], and past every following character that can
     * neither begin a key nor is an IDS operator.
     */
    fun unmatchedRunEnd(chars: CharArray, start: Int, end: Int): Int {
        var width = Utf.ideographicDescriptionSequenceLength(chars, start, end)
        if (width == 0) width = Utf.charCount(Utf.codePointAt(chars, start, end))
        var position = start + width
        val keyStarts = keyStarts
        if (keyStarts != null) {
            while (position < end) {
                val codePoint = Utf.codePointAt(chars, position, end)
                if (codePoint in keyStarts || isIdsOperator(codePoint)) break
                position += Utf.charCount(codePoint)
            }
        } else {
            while (position < end) {
                val codePoint = Utf.codePointAt(chars, position, end)
                if (dict.stopsBulkSkip(codePoint)) break
                position += Utf.charCount(codePoint)
            }
        }
        return position
    }

    /**
     * The operator half of [stopsBulkSkip], for every character a skip
     * passes. Inline, with the range test first, so a character outside the
     * operator block costs two comparisons and no call on every target:
     * Kotlin/Native does not reliably inline small helpers by itself.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun isIdsOperator(codePoint: Int): Boolean =
        codePoint >= Utf.FIRST_IDS_OPERATOR &&
            codePoint <= Utf.LAST_IDS_OPERATOR &&
            Utf.ideographicDescriptionOperatorArity(codePoint) != 0
}
