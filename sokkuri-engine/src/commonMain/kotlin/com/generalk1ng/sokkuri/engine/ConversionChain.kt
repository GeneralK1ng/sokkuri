package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.SokkuriInternalApi

/**
 * An ordered pipeline of [Conversion] stages, the port of OpenCC's
 * `ConversionChain`: stage N's output feeds stage N+1.
 *
 * An empty chain is the identity conversion. A chain whose stages were all
 * filtered out (e.g. tofu-risk dictionaries excluded) still acts as
 * identity, mirroring OpenCC's behavior of dropping `null` conversions.
 */
@SokkuriInternalApi
public class ConversionChain public constructor(
    public val conversions: List<Conversion>,
) {

    /**
     * Converts `chars[start, end)` through every stage, appending to [out].
     * Intermediate stages buffer into a temporary array; the last stage
     * writes into [out] directly (OpenCC's `AppendConvertedSegment`).
     */
    public fun appendConverted(
        chars: CharArray,
        start: Int,
        end: Int,
        out: StringBuilder,
    ) {
        if (conversions.isEmpty()) {
            out.append(chars.concatToString(start, end))
            return
        }
        if (conversions.size == 1) {
            conversions[0].appendConverted(chars, start, end, out)
            return
        }

        var current = chars.copyOfRange(start, end)
        for (index in 0 until conversions.lastIndex) {
            val buffer = StringBuilder(current.size + current.size / 5)
            conversions[index].appendConverted(current, 0, current.size, buffer)
            current = buffer.toString().toCharArray()
        }
        conversions[conversions.lastIndex].appendConverted(current, 0, current.size, out)
    }

    /** Whole-string convenience for the normalization pre-pass and tests. */
    public fun convert(text: String): String {
        if (text.isEmpty() || conversions.isEmpty()) return text
        val chars = text.toCharArray()
        val out = StringBuilder(chars.size + chars.size / 5)
        appendConverted(chars, 0, chars.size, out)
        return out.toString()
    }
}
