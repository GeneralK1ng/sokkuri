package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.Inspection
import com.generalk1ng.sokkuri.SokkuriInternalApi

/**
 * An assembled conversion pipeline, the port of OpenCC's `Converter`
 * abstraction (`SingleStageConverter` / `ConfigBasedConverter`). The
 * upstream `PipelineConverter` is reserved for a future release; the sealed
 * interface already admits it.
 *
 * Implementations are immutable and safe for concurrent use. Assembled
 * exclusively by the config layer ([com.generalk1ng.sokkuri.config.ConfigParser]),
 * never by consumers.
 *
 */
@SokkuriInternalApi
public sealed interface Converter {

    /** Converts [input] according to this pipeline. */
    public fun convert(input: String): String

    /**
     * Converts [input] and reports the intermediate stages; see [Inspection]
     * for the alignment with OpenCC's inspection mode.
     */
    public fun inspect(input: String): Inspection

    /**
     * One segmentation pass followed by one conversion chain — the port of
     * OpenCC's `SingleStageConverter`. With [segmentation] null the whole
     * input is treated as a single segment.
     */
    public class SingleStage public constructor(
        public val segmentation: Segmentation?,
        public val chain: ConversionChain,
    ) : Converter {

        override fun convert(input: String): String {
            val chars = input.toCharArray()
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

        override fun inspect(input: String): Inspection {
            val chars = input.toCharArray()

            val initialSegments: List<String> = if (segmentation == null) {
                listOf(input)
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
                normalizationStages = emptyList(),
                segments = initialSegments,
                stages = stages,
                output = current.joinToString(separator = ""),
            )
        }
    }

    /**
     * A normalization pre-pass wrapping the main converter — the port of
     * OpenCC's `ConfigBasedConverter`, produced when a configuration
     * declares a `normalization` chain. The normalization converter runs
     * over the whole input before the main converter's segmentation.
     */
    public class Normalizing public constructor(
        public val normalization: Converter,
        public val main: Converter,
    ) : Converter {

        override fun convert(input: String): String =
            main.convert(normalization.convert(input))

        override fun inspect(input: String): Inspection {
            val normalized = normalization.inspect(input)
            val mainResult = main.inspect(normalized.output)
            // Flat merge: any pre-pass stages (recursively collected) precede
            // the main chain's stages. A deliberate simplification of
            // upstream's recursive ConversionInspectionResult::pipelineStages.
            return Inspection(
                input = input,
                normalizationStages = normalized.normalizationStages + normalized.stages,
                segments = mainResult.segments,
                stages = mainResult.stages,
                output = mainResult.output,
            )
        }
    }
}
