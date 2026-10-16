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
 * The stop set, the dictionary's [Dictionary.keyStarts] plus those operators,
 * is built once with the stage, as upstream builds its `Utf8SkipTable` once
 * per `PrefixMatch`; the skip then costs one bitmap test per character and no
 * call into the dictionary. A dictionary that cannot enumerate its key starts
 * is asked through [stopsBulkSkip] instead, with the same result.
 *
 * Immutable and safe for concurrent use, like the stage that owns it.
 */
@OptIn(SokkuriInternalApi::class)
internal class BulkSkip(private val dict: Dictionary) {

    /** Where a skip must stop, or `null` when [dict] cannot enumerate its key starts. */
    private val stops: CodePointSet? = dict.keyStarts()?.union(IDS_OPERATORS)

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
        val stops = stops
        if (stops != null) {
            while (position < end) {
                val codePoint = Utf.codePointAt(chars, position, end)
                if (codePoint in stops) break
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

    private companion object {
        /** Every ideographic description operator with non-zero arity: each one stops a skip. */
        val IDS_OPERATORS: CodePointSet = CodePointSet.of(
            *(Utf.FIRST_IDS_OPERATOR..Utf.LAST_IDS_OPERATOR)
                .filter { Utf.ideographicDescriptionOperatorArity(it) != 0 }
                .toIntArray(),
        )
    }
}
