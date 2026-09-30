package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.SokkuriInternalApi

/**
 * One conversion-chain stage: longest-prefix replacement over a single
 * dictionary, the port of OpenCC's `Conversion::AppendConverted`.
 *
 * The scan loop is semantically identical to OpenCC:
 * 1. try the longest dictionary prefix at the cursor; on a hit, emit the
 *    matched value and advance past the key;
 * 2. on a miss, consume one ideographic description sequence (kept atomic,
 *    never split), or one code point when the text is not a complete IDS;
 * 3. extend the emitted run over characters that cannot begin any key
 *    ([Dictionary.mayStartKey]), so long non-CJK stretches cost one prefix
 *    probe per run instead of per character.
 */
@SokkuriInternalApi
public class Conversion public constructor(
    public val dict: Dictionary,
) {

    public fun appendConverted(
        chars: CharArray,
        start: Int,
        end: Int,
        out: StringBuilder,
    ) {
        var i = start
        while (i < end) {
            val match = dict.matchPrefix(chars, i, end)
            if (match != null) {
                out.append(match.value)
                i += match.length
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
            out.append(chars.concatToString(i, j))
            i = j
        }
    }

    /** Convenience whole-string conversion for tests and inspection. */
    public fun convert(text: String): String {
        if (text.isEmpty()) return text
        val chars = text.toCharArray()
        val out = StringBuilder(chars.size + chars.size / 5)
        appendConverted(chars, 0, chars.size, out)
        return out.toString()
    }
}
