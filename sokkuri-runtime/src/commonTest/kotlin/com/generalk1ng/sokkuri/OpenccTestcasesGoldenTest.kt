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

import com.generalk1ng.sokkuri.OpenccTestcasesGoldenTest.Companion.REGISTERED_DIVERGENCES
import kotlin.test.ExperimentalKotlinTestApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Full-corpus golden alignment with OpenCC's `test/testcases/testcases.json`
 * (m1-dictgen-sok §4.2): every ported profile, every upstream expectation,
 * under default [SokkuriOptions]. One test method per profile so platform test
 * reports isolate failures by stem; each failure message carries the case
 * id, the expected output, and the actual output.
 *
 * Converters are created once per profile and shared across test methods
 * (companion scope): dictionary instances flow through the shared cache
 * (architecture constitution D3), so the 16 profiles decode each dictionary
 * only once per process.
 *
 * Expectations that diverge under default SokkuriOptions (tofu-risk dictionaries
 * excluded) are not silently skipped: each is registered in
 * [REGISTERED_DIVERGENCES] with its analysis in the milestone acceptance
 * records (m1-dictgen-sok §7), and the registry itself is validated against
 * the corpus so a stale registration fails loudly.
 */
// Lazy assertion messages (kotlin.test's lambda-form assertTrue) are
// still experimental; fine to adopt in test code.
@OptIn(ExperimentalKotlinTestApi::class)
class OpenccTestcasesGoldenTest {

    @Test
    fun s2tMatchesUpstream() = assertStemMatches(SokkuriConfig.S2T)

    @Test
    fun t2sMatchesUpstream() = assertStemMatches(SokkuriConfig.T2S)

    @Test
    fun s2twMatchesUpstream() = assertStemMatches(SokkuriConfig.S2TW)

    @Test
    fun s2twpMatchesUpstream() = assertStemMatches(SokkuriConfig.S2TWP)

    @Test
    fun tw2sMatchesUpstream() = assertStemMatches(SokkuriConfig.TW2S)

    @Test
    fun tw2spMatchesUpstream() = assertStemMatches(SokkuriConfig.TW2SP)

    @Test
    fun t2twMatchesUpstream() = assertStemMatches(SokkuriConfig.T2TW)

    @Test
    fun tw2tMatchesUpstream() = assertStemMatches(SokkuriConfig.TW2T)

    @Test
    fun s2hkMatchesUpstream() = assertStemMatches(SokkuriConfig.S2HK)

    @Test
    fun s2hkpMatchesUpstream() = assertStemMatches(SokkuriConfig.S2HKP)

    @Test
    fun hk2sMatchesUpstream() = assertStemMatches(SokkuriConfig.HK2S)

    @Test
    fun hk2spMatchesUpstream() = assertStemMatches(SokkuriConfig.HK2SP)

    @Test
    fun t2hkMatchesUpstream() = assertStemMatches(SokkuriConfig.T2HK)

    @Test
    fun hk2tMatchesUpstream() = assertStemMatches(SokkuriConfig.HK2T)

    @Test
    fun jp2tMatchesUpstream() = assertStemMatches(SokkuriConfig.JP2T)

    @Test
    fun t2jpMatchesUpstream() = assertStemMatches(SokkuriConfig.T2JP)

    /**
     * The three registered t2s divergences are exactly the corpus's tofu
     * probes (chars whose mappings live only in `TSCharactersExt`,
     * `may_output_tofu` in upstream t2s.json): with tofu-risk dictionaries
     * included they must convert exactly as upstream expects, pinning the
     * analysis that the default-SokkuriOptions divergence is the tofu toggle and
     * nothing else.
     */
    @Test
    fun t2sTofuDivergencesResolveWithTofuDictionariesIncluded() {
        val config = SokkuriConfig.T2S
        val registered = REGISTERED_DIVERGENCES.getValue(config.stem)
        val cases = OpenccTestcases.byStem.getValue(config.stem).filter { it.id in registered }
        assertEquals(registered.size, cases.size, "every registered divergence must exist in the corpus")
        val converter = Sokkuri.create(
            config,
            SokkuriOptions { includeTofuRiskDictionaries = true },
        )
        val mismatches = cases.filter { converter.convert(it.input) != it.expected }
        assertTrue(mismatches.isEmpty()) {
            "${config.stem}: registered tofu divergences do not resolve with tofu dictionaries included: " +
                    mismatches.joinToString(" │ ") { "${it.id} «${it.input}»" }
        }
    }

    private fun assertStemMatches(config: SokkuriConfig) {
        val cases = OpenccTestcases.byStem.getValue(config.stem)
        val corpusIds = cases.map { it.id }.toSet()
        val registered = REGISTERED_DIVERGENCES[config.stem].orEmpty()
        val stale = registered - corpusIds
        assertTrue(stale.isEmpty()) {
            "${config.stem}: registered diverges not present in the corpus: $stale"
        }
        val converter = CONVERTERS.getValue(config)
        // A registration must be necessary: a registered case that now passes
        // under default SokkuriOptions is a stale registration and must be dropped.
        val unjustified = cases.filter { it.id in registered && converter.convert(it.input) == it.expected }
        assertTrue(unjustified.isEmpty()) {
            "${config.stem}: registered divergences that pass under default SokkuriOptions: " +
                    unjustified.map { it.id }
        }
        val active = cases.filter { it.id !in registered }
        val mismatches = active.filter { converter.convert(it.input) != it.expected }
        assertTrue(mismatches.isEmpty()) {
            "${config.stem}: ${mismatches.size} of ${active.size} expectations mismatched " +
                    "(showing up to 10) " + mismatches.take(10).joinToString(" │ ") { case ->
                "${case.id} «${case.input}» → expected «${case.expected}», got " +
                        "«${converter.convert(case.input)}»"
            }
        }
    }

    private companion object {

        /** One converter per profile, shared across all test methods. */
        private val CONVERTERS: Map<SokkuriConfig, Sokkuri> =
            SokkuriConfig.entries.associateWith { Sokkuri.create(it) }

        /**
         * Expectations that diverge under default [SokkuriOptions] because their
         * only path to upstream output goes through a tofu-risk dictionary
         * (`TSCharactersExt`, flagged `may_output_tofu` in upstream
         * `t2s.json`; excluded unless `includeTofuRiskDictionaries`).
         *
         * Individually analyzed (m1-dictgen-sok §7):
         * - `BYVoid_OpenCC_PR_1228`: 殢→𣨼 — TSCharactersExt:1298
         * - `BYVoid_OpenCC_PR_1229`: 圞→𪢮 — TSCharactersExt:636
         * - `BYVoid_OpenCC_PR_464`: 樠→𣗊 — TSCharactersExt:1212
         *
         * Necessity is enforced by [assertStemMatches]; resolution under
         * the tofu-including option by `t2sTofuDivergencesResolveWithTofuDictionariesIncluded`.
         */
        private val REGISTERED_DIVERGENCES: Map<String, Set<String>> = mapOf(
            "t2s" to setOf(
                "BYVoid_OpenCC_PR_1228_existing_behaviors",
                "BYVoid_OpenCC_PR_1229_existing_behaviors",
                "BYVoid_OpenCC_PR_464_xi_vs_xi",
            ),
        )
    }
}
