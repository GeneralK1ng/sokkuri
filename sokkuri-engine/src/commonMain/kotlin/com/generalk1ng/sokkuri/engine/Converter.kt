package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.SokkuriInternalApi

import com.generalk1ng.sokkuri.Inspection

/**
 * The assembled conversion pipeline for one configuration, the port of
 * OpenCC's `SingleStageConverter` (optionally wrapped by
 * `ConfigBasedConverter` when the config declares a `normalization`
 * pre-pass):
 *
 * ```
 * input → normalization? → segmentation? → per-segment conversion chain
 * ```
 *
 * Instances are immutable and thread-safe.
 */
@SokkuriInternalApi
public class Converter public constructor(
    public val normalization: ConversionChain?,
    public val segmentation: Segmentation?,
    public val chain: ConversionChain,
) {

    public fun convert(input: String): String {
        val normalized = normalization?.convert(input) ?: input
        val chars = normalized.toCharArray()
        val out = StringBuilder(chars.size + chars.size / 5)
        if (segmentation == null) {
            chain.appendConverted(chars, 0, chars.size, out)
        } else {
            for (range in segmentation.segment(chars)) {
                chain.appendConverted(chars, range.first, range.last + 1, out)
            }
        }
        return out.toString()
    }

    public fun inspect(input: String): Inspection {
        val normalized = normalization?.convert(input) ?: input
        val chars = normalized.toCharArray()

        val initialSegments: List<String> = if (segmentation == null) {
            listOf(normalized)
        } else {
            segmentation.segment(chars).map { chars.concatToString(it.first, it.last + 1) }
        }

        var current = initialSegments
        val stages = ArrayList<Inspection.Stage>(chain.conversions.size)
        for ((index, conversion) in chain.conversions.withIndex()) {
            current = current.map { conversion.convert(it) }
            stages.add(Inspection.Stage(index + 1, current))
        }
        return Inspection(
            input = input,
            segments = initialSegments,
            stages = stages,
            output = current.joinToString(separator = ""),
        )
    }
}
