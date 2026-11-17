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

package com.generalk1ng.sokkuri

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Structural smoke test for `inspect()`: on corpus
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

    /**
     * `convert` takes faster paths than `inspect`, which converts segment by
     * segment and stage by stage: the four segmented s2* profiles fuse
     * segmentation with stage 1. Both must give the same text, on every
     * profile and tofu setting, for every corpus input and for IDS text,
     * which the corpus lacks.
     */
    @Test
    fun convertAgreesWithInspectionOnEveryProfile() {
        val inputs = OpenccTestcases.byStem.values.flatten().map { it.input }.distinct() + listOf(
            "⿰干发头发干燥", "头发⿱艹干燥鼠标", "鼠标⿰⿱干发后里", "里面⿺辶干的头发", "干⿰",
        )
        for (config in SokkuriConfig.entries) {
            for (tofu in listOf(false, true)) {
                val converter = Sokkuri.create(config, SokkuriOptions { includeTofuRiskDictionaries = tofu })
                for (input in inputs) {
                    assertEquals(
                        converter.inspect(input).output,
                        converter.convert(input),
                        "${config.stem} (tofu $tofu): convert vs inspect for $input",
                    )
                }
            }
        }
    }
}
