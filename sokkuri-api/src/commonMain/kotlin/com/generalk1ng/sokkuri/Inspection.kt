package com.generalk1ng.sokkuri

/**
 * A structural trace of one conversion, aligned with OpenCC's
 * `ConversionInspectionResult`: the segmentation outcome plus the output of
 * every conversion-chain stage, in application order.
 *
 * @property input the original input text.
 * @property segments the segmentation result before the conversion chain;
 *   a single segment equal to [input] when the profile has no segmentation.
 * @property stages one entry per conversion-chain stage, in order.
 * @property output the final converted text; equal to the concatenation of
 *   the last stage's segments (or of [segments] when the chain is empty).
 */
public class Inspection public constructor(
    public val input: String,
    public val segments: List<String>,
    public val stages: List<Stage>,
    public val output: String,
) {

    /**
     * The output of a single conversion-chain stage.
     *
     * @property index one-based stage position, matching OpenCC's
     *   `ConversionInspectionStage::index`.
     * @property segments the text segments as they were after this stage.
     */
    public class Stage public constructor(
        public val index: Int,
        public val segments: List<String>,
    )
}
