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

package com.generalk1ng.sokkuri.upstreamdiff

import com.generalk1ng.sokkuri.Inspection
import kotlinx.serialization.Serializable

/**
 * Upstream's `ConversionInspectionResult` exactly as the harness's
 * `inspect` subcommand serializes it: a tree, because a config-backed
 * converter with normalization is a two-stage pipeline
 * (`ConfigBasedConverter`), each stage carrying its own segments and
 * chain trace.
 */
@Serializable
internal data class UpstreamInspection(
    val segments: List<String>,
    val stages: List<UpstreamStage>,
    val pipelineStages: List<UpstreamInspection>,
    val output: String,
)

/** One `ConversionInspectionStage`: a one-based index and the segments after it. */
@Serializable
internal data class UpstreamStage(val index: Int, val segments: List<String>)

/**
 * An inspection in Sokkuri's flat shape, the common ground of the
 * comparison: [Inspection] reports normalization as stages preceding the
 * main chain instead of upstream's nested pipeline, so the upstream tree is
 * flattened the same way before the two are compared field by field.
 */
internal data class FlatInspection(
    val normalizationStages: List<FlatStage>,
    val segments: List<String>,
    val stages: List<FlatStage>,
    val output: String,
) {

    /**
     * The first field in which [actual] differs from this expected
     * inspection, rendered for a mismatch report, or `null` when equal.
     */
    fun firstDifference(actual: FlatInspection): String? = when {
        normalizationStages != actual.normalizationStages ->
            "normalizationStages: upstream=$normalizationStages sokkuri=${actual.normalizationStages}"
        segments != actual.segments -> "segments: upstream=$segments sokkuri=${actual.segments}"
        stages != actual.stages -> "stages: upstream=$stages sokkuri=${actual.stages}"
        output != actual.output -> "output: upstream=$output sokkuri=${actual.output}"
        else -> null
    }

    companion object {

        fun of(inspection: Inspection): FlatInspection = FlatInspection(
            normalizationStages = inspection.normalizationStages.map(::flatStage),
            segments = inspection.segments,
            stages = inspection.stages.map(::flatStage),
            output = inspection.output,
        )

        /**
         * Flattens upstream's tree as Sokkuri's `Converter.Normalizing`
         * does: every pipeline stage but the last contributes its own
         * normalization and chain stages, in order, as normalization; the
         * last provides the segments and the main stages. A converter
         * without pipeline stages is already flat. The top-level output
         * stays the pipeline's own.
         */
        fun of(upstream: UpstreamInspection): FlatInspection {
            if (upstream.pipelineStages.isEmpty()) {
                return FlatInspection(
                    normalizationStages = emptyList(),
                    segments = upstream.segments,
                    stages = upstream.stages.map { FlatStage(it.index, it.segments) },
                    output = upstream.output,
                )
            }
            val normalization = ArrayList<FlatStage>()
            for (stage in upstream.pipelineStages.dropLast(1)) {
                val flat = of(stage)
                normalization += flat.normalizationStages
                normalization += flat.stages
            }
            val main = of(upstream.pipelineStages.last())
            return FlatInspection(
                normalizationStages = normalization + main.normalizationStages,
                segments = main.segments,
                stages = main.stages,
                output = upstream.output,
            )
        }

        private fun flatStage(stage: Inspection.Stage): FlatStage = FlatStage(stage.index, stage.segments)
    }
}

/** A stage in the common shape: its one-based index and the segments after it. */
internal data class FlatStage(val index: Int, val segments: List<String>)
