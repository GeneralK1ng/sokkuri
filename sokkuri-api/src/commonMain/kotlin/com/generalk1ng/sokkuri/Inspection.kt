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

/**
 * A structural trace of one conversion, aligned with OpenCC's
 * `ConversionInspectionResult`: the segmentation outcome plus the output of
 * every conversion-chain stage, in application order.
 *
 * Deliberately flatter than upstream: normalization pre-passes are reported
 * as [normalizationStages] preceding the main chain, instead of the nested
 * `ConversionInspectionResult::pipelineStages`. A future multi-stage
 * `Pipeline` converter generalizes this into a tree.
 *
 * @property input the original input text.
 * @property normalizationStages output of every normalization pre-pass
 *   stage, in application order; empty when the converter has no
 *   normalization (the common case).
 * @property segments the segmentation result after normalization; a single
 *   segment equal to the normalized input when the profile has no
 *   segmentation.
 * @property stages one entry per conversion-chain stage, in order, indices
 *   starting at 1 (matching OpenCC's `ConversionInspectionStage::index`).
 * @property output the final converted text; equal to the concatenation of
 *   the last stage's segments (or of [segments] when the chain is empty).
 */
public class Inspection public constructor(
    public val input: String,
    public val normalizationStages: List<Stage>,
    public val segments: List<String>,
    public val stages: List<Stage>,
    public val output: String,
) {

    /**
     * The output of a single conversion stage.
     *
     * @property index one-based stage position.
     * @property segments the text segments as they were after this stage.
     */
    public class Stage public constructor(
        public val index: Int,
        public val segments: List<String>,
    )
}
