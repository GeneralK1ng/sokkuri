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
 * upstream commit recorded in `testcases/UPSTREAM`. Stems outside the
 * ported [SokkuriConfig] profiles (the `*seal*` families, deliberately not ported)
 * are skipped defensively via [SokkuriConfig.fromStem].
 */
@OptIn(SokkuriInternalApi::class)
internal object OpenccTestcases {

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
        for (element in root.getValue("cases").jsonArray) {
            val obj = element.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val input = obj.getValue("input").jsonPrimitive.content
            for ((stem, value) in obj.getValue("expected").jsonObject) {
                val profile = SokkuriConfig.fromStem(stem) ?: continue
                byStem.getOrPut(profile.stem) { ArrayList() }
                    .add(Case(id, input, value.jsonPrimitive.content))
            }
        }
        val missing = SokkuriConfig.entries.map { it.stem } - byStem.keys
        check(missing.isEmpty()) { "no upstream cases for ported profiles: $missing" }
        return byStem
    }
}
