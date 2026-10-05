package com.generalk1ng.sokkuri

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Structural smoke test for `inspect()` (m1-dictgen-sok §4.3): on corpus
 * cases whose conversion is golden-verified, the [Inspection] must agree
 * with [Sokkuri.convert] and hold the documented tiling invariants:
 *
 * - `output` equals the golden expectation (and therefore `convert`);
 * - segments tile the normalized input (normalization pre-passes precede
 *   segmentation), and the final stage's segments tile `output`;
 * - stage indices are one-based and continuous (OpenCC's
 *   `ConversionInspectionStage::index` convention);
 * - every stage maps segments one-to-one (the chain applies each conversion
 *   per segment), so segment counts are preserved throughout.
 *
 * Profiles are sampled across pipeline shapes: plain chains (s2t, t2s),
 * mmseg segmentation + regional vocabulary (s2twp, tw2sp).
 */
class InspectionSmokeTest {

    @Test
    fun inspectionAlignsWithConvertAcrossPipelineShapes() {
        for (stem in listOf("s2t", "t2s", "s2twp", "tw2sp")) {
            val config = SokkuriConfig.fromStem(stem)
            checkNotNull(config) { "unknown stem: $stem" }
            // Prefer a case whose text actually moves through the pipeline.
            val case = OpenccTestcases.byStem.getValue(stem)
                .firstOrNull { it.expected != it.input }
                ?: OpenccTestcases.byStem.getValue(stem).first()
            val converter = Sokkuri.create(config)

            val inspection = converter.inspect(case.input)
            assertEquals(case.expected, inspection.output, "$stem: inspection output")
            assertEquals(converter.convert(case.input), inspection.output, "$stem: inspect vs convert")

            val normalized = inspection.normalizationStages
                .lastOrNull()?.segments?.joinToString("") ?: inspection.input
            assertEquals(normalized, inspection.segments.joinToString(""), "$stem: segments tile the normalized input")

            assertEquals(
                (1..inspection.stages.size).toList(),
                inspection.stages.map { it.index },
                "$stem: stage indices must be one-based and continuous",
            )

            val effective = inspection.stages.lastOrNull()?.segments ?: inspection.segments
            assertEquals(inspection.output, effective.joinToString(""), "$stem: output = final segments concatenated")

            for (stage in inspection.stages) {
                assertEquals(
                    inspection.segments.size,
                    stage.segments.size,
                    "$stem: stage ${stage.index} must map segments one-to-one",
                )
            }
        }
    }
}
