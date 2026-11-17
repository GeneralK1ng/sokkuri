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
 * [MaxMatchSegmentation] fused with the first conversion stage, for chains
 * whose first stage begins with the segmentation dictionary: the segmented
 * s2* profiles, where segmentation and stage 1 both query the STPhrases
 * union. Sokkuri's own optimization with no OpenCC counterpart. Its output
 * equals [ConversionChain.appendConvertedSegments] over
 * [MaxMatchSegmentation.segment], the port of upstream's `SegmentText`
 * followed by `Conversion::AppendConverted` per segment;
 * [Converter.SingleStage.inspect] stays on that reference path.
 *
 * Let S be the segmentation dictionary and F the first stage's dictionary:
 * either S itself, or a SHORT_CIRCUIT group whose first member is S and
 * whose other members form the rest R ([sameMatches] decides "is S").
 * Segmentation emits two kinds of segment, and F's conversion of each is
 * known without asking S again:
 *
 * - A matched segment `[i, i + L)`, where L is the length of S's longest
 *   match at `i` over the whole input. Ending the text at `i + L` cannot
 *   change that match: it only removes keys longer than L, and the winner
 *   is not one of them. So F matches the same key at `i`, covers the
 *   segment in one step and writes the value segmentation already found.
 * - An unmatched run `[a, b)` without an IDS operator (U+2FF0 to U+2FFF).
 *   No IDS can begin in it, so segmentation walked it one code point at a
 *   time, and every code-point position either missed S or was skipped
 *   because its code point begins no key of S. Stage 1 also stops only at
 *   code-point positions there (keys never split a surrogate pair, and
 *   neither does the boundary `b`, where a key of S begins), and S, ended
 *   at `b`, misses at each of them. So F answers what R answers at every
 *   step, and converting the run with R equals converting it with F. With
 *   no R (F is S), F misses throughout and the run is copied unchanged.
 * - A run containing an operator is converted with F as before. A key of R
 *   may begin at an operator or run into an IDS, and stage 1 then stops
 *   inside an IDS that segmentation consumed whole, where S was never asked.
 *   Segmentation's own bulk skip stops at every operator, so an operator in
 *   the run lies either at a position segmentation asked S about, where
 *   this scan notices it, or inside an IDS that begins at one.
 *
 * This saves the second query of S: one hit per matched segment and one
 * miss per position of a run that segmentation asked about.
 *
 * Immutable and safe for concurrent use, like the converter that owns it.
 */
@OptIn(SokkuriInternalApi::class)
internal class SegmentReuse private constructor(
    segmentation: MaxMatchSegmentation,
    private val chain: ConversionChain,
    rest: Dictionary?,
) {

    private val dict: Dictionary = segmentation.dict
    private val bulkSkip: BulkSkip = segmentation.bulkSkip
    private val firstStage: Conversion = chain.conversions[0]

    /** Stage 1 for runs without an operator; `null` when F is S alone. */
    private val rest: Conversion? = if (rest == null) null else Conversion(rest)

    /**
     * Converts [chars] through the whole chain, appending to [out]: the same
     * output as [ConversionChain.appendConvertedSegments] over the
     * segmentation's segments.
     */
    fun appendConverted(chars: CharArray, out: StringBuilder) {
        if (chars.isEmpty()) return
        if (chain.conversions.size == 1) {
            appendFirstStage(chars, out, null)
            return
        }
        val buffer = StringBuilder(chars.size + chars.size / 5)
        val starts = SegmentStarts(chars.size / 4 + 4)
        appendFirstStage(chars, buffer, starts)
        // Stage 1's segment outputs tile the buffer, so each one ends where
        // the next begins.
        val count = starts.count
        val begins = starts.values
        val ends = IntArray(count)
        for (segment in 0 until count - 1) ends[segment] = begins[segment + 1]
        ends[count - 1] = buffer.length
        val stage1 = CharArray(buffer.length)
        buffer.toCharArray(stage1, 0, 0, buffer.length)
        chain.appendStagesFrom(1, stage1, begins, ends, count, out)
    }

    /**
     * Segments [chars] as [MaxMatchSegmentation.segment] does and writes
     * stage 1's output of each segment to [out], recording in [starts], when
     * given, where each segment's output begins.
     */
    private fun appendFirstStage(chars: CharArray, out: StringBuilder, starts: SegmentStarts?) {
        val end = chars.size
        var runStart = 0
        var runHasOperator = false
        var i = 0
        while (i < end) {
            val match = dict.matchPrefix(chars, i, end)
            if (match != null) {
                if (runStart < i) appendRun(chars, runStart, i, runHasOperator, out, starts)
                starts?.add(out.length)
                out.append(match.value)
                i += match.length
                runStart = i
                runHasOperator = false
                continue
            }
            val unit = chars[i].code
            if (unit >= Utf.FIRST_IDS_OPERATOR && unit <= Utf.LAST_IDS_OPERATOR) runHasOperator = true
            i = bulkSkip.unmatchedRunEnd(chars, i, end)
        }
        if (runStart < end) appendRun(chars, runStart, end, runHasOperator, out, starts)
    }

    private fun appendRun(
        chars: CharArray,
        start: Int,
        end: Int,
        hasOperator: Boolean,
        out: StringBuilder,
        starts: SegmentStarts?,
    ) {
        starts?.add(out.length)
        val rest = rest
        when {
            hasOperator -> firstStage.appendConverted(chars, start, end, out)
            rest != null -> rest.appendConverted(chars, start, end, out)
            else -> out.appendRange(chars, start, end)
        }
    }

    /** A growable list of segment output offsets, without boxing. */
    private class SegmentStarts(capacity: Int) {
        var values: IntArray = IntArray(capacity)
        var count: Int = 0

        fun add(offset: Int) {
            if (count == values.size) values = values.copyOf(count * 2)
            values[count] = offset
            count += 1
        }
    }

    companion object {

        /**
         * The fused path for [segmentation] and [chain], or `null` when the
         * chain's first stage neither is the segmentation dictionary nor a
         * SHORT_CIRCUIT group that begins with it.
         */
        fun of(segmentation: Segmentation?, chain: ConversionChain): SegmentReuse? {
            if (segmentation !is MaxMatchSegmentation || chain.conversions.isEmpty()) return null
            val segmentationDict = segmentation.dict
            val first = chain.conversions[0].dict
            if (sameMatches(first, segmentationDict)) return SegmentReuse(segmentation, chain, null)
            if (first !is DictGroup || first.policy != GroupMatchPolicy.SHORT_CIRCUIT) return null
            val members = first.members
            if (!sameMatches(members[0], segmentationDict)) return null
            val rest = when (members.size) {
                1 -> null
                2 -> members[1]
                else -> DictGroup(members.asList().subList(1, members.size), GroupMatchPolicy.SHORT_CIRCUIT)
            }
            return SegmentReuse(segmentation, chain, rest)
        }
    }
}

/**
 * Whether [a] and [b] answer every query alike because they are built
 * alike: the same instance, or groups with the same policy over members
 * that are alike pairwise, in order. Two distinct leaves never count as
 * alike, even with equal content. That is enough because the config layer
 * takes every leaf from the process-wide dictionary cache, where one file
 * is one instance, while it builds each group afresh.
 */
@OptIn(SokkuriInternalApi::class)
internal fun sameMatches(a: Dictionary, b: Dictionary): Boolean {
    if (a === b) return true
    if (a !is DictGroup || b !is DictGroup) return false
    if (a.policy != b.policy || a.members.size != b.members.size) return false
    for (index in a.members.indices) {
        if (!sameMatches(a.members[index], b.members[index])) return false
    }
    return true
}
