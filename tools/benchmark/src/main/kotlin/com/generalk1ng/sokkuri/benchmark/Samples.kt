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

package com.generalk1ng.sokkuri.benchmark

import com.generalk1ng.sokkuri.SokkuriConfig

/**
 * The embedded sample set of the benchmark runs:
 * fixed and in-repo on purpose — a benchmark must measure a stable
 * workload, not whatever the current upstream corpus says. `medium` and
 * `long` run against every profile so cross-profile numbers stay
 * comparable; the special samples stress one pipeline aspect and run only
 * on their tagged profile.
 *
 * The texts live in `tools/shared-resources/samples/<id>.txt`, which
 * tools:upstream-diff reads too, so its reference timings of upstream run
 * on exactly these texts.
 */
@ConsistentCopyVisibility
internal data class Sample internal constructor(
    /** Stable id, echoed in the `[bench]` output line. */
    val id: String,
    /** The exact probe text; never locale- or environment-dependent. */
    val text: String,
    /** Default measured iterations; `--iterations` overrides. */
    val defaultIterations: Int = DEFAULT_ITERATIONS,
) {
    internal companion object {
        /** Default measured iterations for a regular sample. */
        internal const val DEFAULT_ITERATIONS: Int = 20_000

        /**
         * The phrase-stress sample is ~10× costlier per op (219 chars over
         * the 49k-entry STPhrases table), so its default is downshifted.
         */
        internal const val PHRASE_ITERATIONS: Int = 5_000

        /** The long sample is ~17× the length of `medium`, so its default is downshifted further. */
        internal const val LONG_ITERATIONS: Int = 2_000
    }
}

/** Sample definitions plus the profile→samples selection rule. */
internal object Samples {

    /**
     * The pilot probe text, carried verbatim from the original baseline so
     * the convert numbers stay directly comparable across runs.
     */
    internal val MEDIUM: Sample = Sample(
        id = "medium",
        text = sampleText("medium"),
    )

    /**
     * Sustained throughput: an original ~800-character article
     * of everyday and technical prose (six paragraphs, newline-separated)
     * whose vocabulary spans the regional phrase tables and the large
     * one-to-many groups (发, 干, 后, 出). Per-op fixed costs vanish at this
     * length, so it measures what a long document costs; it runs on every
     * profile, like `medium`, so throughput stays comparable across them.
     */
    internal val LONG: Sample = Sample(
        id = "long",
        text = sampleText("long"),
        defaultIterations = Sample.LONG_ITERATIONS,
    )

    /** Short input: per-op fixed costs (call overhead, segmentation setup) dominate. */
    private val SHORT: Sample = Sample(id = "short", text = sampleText("short"))

    /**
     * The upstream "noodle sentence" (golden corpus case `s2t_ximian`,
     * input side, verbatim): 219 chars of 2–7-char phrase keys against
     * the largest dictionary — the longest-prefix worst case.
     */
    internal val PHRASES: Sample = Sample(
        id = "phrases",
        text = sampleText("phrases"),
        defaultIterations = Sample.PHRASE_ITERATIONS,
    )

    /** Regional vocabulary + mmseg segmentation (滑鼠/軟體). */
    private val TWP: Sample = Sample(id = "twp", text = sampleText("twp"))

    /** HK variant chain (恒生銀行發佈財報 style). */
    private val HK: Sample = Sample(id = "hk", text = sampleText("hk"))

    /** Special samples keyed by their tagged profile; `medium` is implicit for all. */
    private val SPECIALS: Map<SokkuriConfig, List<Sample>> = mapOf(
        SokkuriConfig.S2T to listOf(SHORT, PHRASES),
        SokkuriConfig.S2TWP to listOf(TWP),
        SokkuriConfig.S2HK to listOf(HK),
    )

    /**
     * The selection rule: `medium` and `long` plus the specials tagged for
     * [profile] (none for profiles without a stress sample).
     */
    internal fun forProfile(profile: SokkuriConfig): List<Sample> =
        listOf(MEDIUM, LONG) + SPECIALS[profile].orEmpty()
}

/**
 * The text of sample [id], from the shared `samples/` resources. Each file
 * ends with one newline that is not part of the sample, so editors that
 * insist on a final newline cannot change what is measured.
 */
internal fun sampleText(id: String): String {
    val resource = checkNotNull(Sample::class.java.getResourceAsStream("/samples/$id.txt")) {
        "missing sample resource samples/$id.txt (tools/shared-resources)"
    }
    val text = resource.use { it.readBytes().decodeToString() }
    check(text.endsWith("\n")) { "samples/$id.txt must end with a newline" }
    return text.removeSuffix("\n")
}
