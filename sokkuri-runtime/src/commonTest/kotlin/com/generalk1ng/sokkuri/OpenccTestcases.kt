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

import com.generalk1ng.sokkuri.config.JsonSupport
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The upstream OpenCC acceptance corpus, parsed once and shared by the
 * golden harness ([OpenccTestcasesGoldenTest]) and the inspection smoke
 * test ([InspectionSmokeTest]).
 *
 * Source: `OpenCC/test/testcases/testcases.json` (JSONC — comments and
 * trailing commas), copied verbatim into commonTest resources with the
 * upstream commit recorded in `testcases/UPSTREAM`. Every stem in the corpus
 * must be a ported [SokkuriConfig] profile or registered in [UNPORTED_STEMS]:
 * an unregistered stem means upstream added a profile, and a registration the
 * corpus no longer uses is stale; both fail loudly instead of being skipped.
 */
@OptIn(SokkuriInternalApi::class)
internal object OpenccTestcases {

    /**
     * Upstream stems deliberately not ported: the seal family
     * (docs/architecture.md §7 #4). Declared before [byStem], whose
     * initializer reads it.
     */
    private val UNPORTED_STEMS: Set<String> = setOf("s2seal", "seal2t", "t2seal")

    /** One corpus entry for one profile: the expected conversion under `stem`. */
    internal data class Case(
        /** Upstream case id, e.g. `"case_055_s2t"`. */
        val id: String,
        val input: String,
        val expected: String,
    )

    /** Cases grouped by profile stem; every ported profile must have coverage. */
    internal val byStem: Map<String, List<Case>> = load()

    /** Total number of (case, stem) expectations across all ported profiles. */
    internal val expectationCount: Int = byStem.values.sumOf { it.size }

    private fun load(): Map<String, List<Case>> {
        val bytes = readTestResource("testcases/testcases.json")
        checkNotNull(bytes) { "testcases/testcases.json missing from the test resources" }
        // The corpus is JSONC exactly in OpenCC's config dialect: comments
        // stripped by the same preprocessor, trailing commas native.
        val cleaned = JsonSupport.stripComments(bytes.decodeToString())
        val root = JsonSupport.opencc.parseToJsonElement(cleaned).jsonObject
        val byStem = HashMap<String, ArrayList<Case>>()
        val unknownStems = HashSet<String>()
        for (element in root.getValue("cases").jsonArray) {
            val obj = element.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val input = obj.getValue("input").jsonPrimitive.content
            for ((stem, value) in obj.getValue("expected").jsonObject) {
                val profile = SokkuriConfig.fromStem(stem)
                if (profile == null) {
                    unknownStems.add(stem)
                    continue
                }
                byStem.getOrPut(profile.stem) { ArrayList() }
                    .add(Case(id, input, value.jsonPrimitive.content))
            }
        }
        val missing = SokkuriConfig.entries.map { it.stem } - byStem.keys
        check(missing.isEmpty()) { "no upstream cases for ported profiles: $missing" }
        val unregistered = (unknownStems - UNPORTED_STEMS).sorted()
        check(unregistered.isEmpty()) {
            "upstream cases for stems neither ported nor registered as unported: $unregistered"
        }
        val stale = (UNPORTED_STEMS - unknownStems).sorted()
        check(stale.isEmpty()) { "registered unported stems absent from the corpus: $stale" }
        return byStem
    }
}
