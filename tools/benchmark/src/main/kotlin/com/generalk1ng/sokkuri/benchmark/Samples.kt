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
 * The embedded sample set of the benchmark runs (m2-benchmark.md §4):
 * fixed and in-repo on purpose — a benchmark must measure a stable
 * workload, not whatever the current upstream corpus says. `medium` runs
 * against every profile so cross-profile numbers stay comparable; the
 * special samples stress one pipeline aspect and run only on their tagged
 * profile.
 */
@ConsistentCopyVisibility
internal data class Sample internal constructor(
    /** Stable id, echoed in the `[bench]` output line. */
    val id: String,
    /** The exact probe text; never locale- or environment-dependent. */
    val text: String,
    /** Default measured iterations (§3.1); `--iterations` overrides. */
    val defaultIterations: Int = DEFAULT_ITERATIONS,
) {
    internal companion object {
        /** §3.1 default measured iterations for a regular sample. */
        internal const val DEFAULT_ITERATIONS: Int = 20_000

        /**
         * The phrase-stress sample is ~10× costlier per op (219 chars over
         * the 49k-entry STPhrases table), so §4 downshifts its default.
         */
        internal const val PHRASE_ITERATIONS: Int = 5_000
    }
}

/** Sample definitions plus the profile→samples selection rule (§3.2/§4). */
internal object Samples {

    /**
     * The pilot probe text, carried verbatim from the M1 baseline so the
     * convert numbers stay directly comparable with m1-dictgen-sok.md §7.
     */
    internal val MEDIUM: Sample = Sample(
        id = "medium",
        text = "鼠标里面的硅二极管坏了，需要更换。软件开发和硬件维护都需要严谨的态度。" +
                "自干五扇风耳朵，铺眉搧眼。",
    )

    /** Short input: per-op fixed costs (call overhead, segmentation setup) dominate. */
    private val SHORT: Sample = Sample(id = "short", text = "繁體中文轉換測試")

    /**
     * The upstream "noodle sentence" (golden corpus case `s2t_ximian`,
     * input side, verbatim): 219 chars of 2–7-char phrase keys against
     * the largest dictionary — the longest-prefix worst case.
     */
    internal val PHRASES: Sample = Sample(
        id = "phrases",
        text = "细面 宽面 油泼面 臊子面 裤带面 重庆小面 肉酱面 酸菜鱼面 肥肠面 炒码面 牛腩面 牛筋面 " +
                "三鲜面 番茄面 羊肉面 猪肉面 卤肉面 鲁肉面 虾球面 虾仁面 拉揸面 杂烩面 拉杂面 嗱喳面 " +
                "强棒面 酱面 葱油面 油酱面 焖面 焗面 焗猪扒面 猫耳面 削筋面 浆水面 拨鱼面 刀拨面 " +
                "银丝面 菠菜面 黄鱼面 大肠面 焖肉面 大排面 腰花面 咖喱面 麻辣面 酸辣面 火鸡面 " +
                "锅烧面 切仔面 卤味面 炒鳝面 沾面 手工面 出前一丁面 意大利面",
        defaultIterations = Sample.PHRASE_ITERATIONS,
    )

    /** Regional vocabulary + mmseg segmentation (滑鼠/軟體). */
    private val TWP: Sample = Sample(id = "twp", text = "鼠标和软件")

    /** HK variant chain (恒生銀行發佈財報 style). */
    private val HK: Sample = Sample(id = "hk", text = "恒生銀行和恒大集團發佈財報")

    /** Special samples keyed by their tagged profile; `medium` is implicit for all. */
    private val SPECIALS: Map<SokkuriConfig, List<Sample>> = mapOf(
        SokkuriConfig.S2T to listOf(SHORT, PHRASES),
        SokkuriConfig.S2TWP to listOf(TWP),
        SokkuriConfig.S2HK to listOf(HK),
    )

    /**
     * §3.2 selection rule: `medium` plus the specials tagged for
     * [profile] (none for profiles without a stress sample).
     */
    internal fun forProfile(profile: SokkuriConfig): List<Sample> =
        listOf(MEDIUM) + SPECIALS[profile].orEmpty()
}
