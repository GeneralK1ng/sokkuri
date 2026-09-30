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
