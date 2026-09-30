package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.SokkuriInternalApi

/**
 * Maximal-forward-matching segmentation, the port of OpenCC's
 * `MaxMatchSegmentation` / `SegmentText`:
 *
 * - positions where the dictionary matches are emitted as standalone
 *   segments (flush the pending plain-text run first), protecting phrase
 *   boundaries for later conversion stages;
 * - unmatched text accumulates into runs, extended by the same
 *   cannot-start-a-key bulk skip as [Conversion], and by ideographic
 *   description sequences kept atomic.
 */
@SokkuriInternalApi
public class MaxMatchSegmentation public constructor(
    private val dict: Dictionary,
) : Segmentation {

    override fun segment(chars: CharArray): List<IntRange> {
        val segments = ArrayList<IntRange>()
        if (chars.isEmpty()) return segments

        val end = chars.size
        var runStart = 0
        var i = 0
        while (i < end) {
            val match = dict.matchPrefix(chars, i, end)
            if (match != null) {
                if (runStart < i) segments.add(runStart..<i)
                segments.add(i..<i + match.length)
                i += match.length
                runStart = i
                continue
            }

            var width = Utf.ideographicDescriptionSequenceLength(chars, i, end)
            if (width == 0) width = Utf.charCount(Utf.codePointAt(chars, i, end))

            var j = i + width
            while (j < end) {
                val codePoint = Utf.codePointAt(chars, j, end)
                if (dict.mayStartKey(codePoint)) break
                j += Utf.charCount(codePoint)
            }
            i = j
        }
        if (runStart < end) segments.add(runStart..<end)
        return segments
    }
}
