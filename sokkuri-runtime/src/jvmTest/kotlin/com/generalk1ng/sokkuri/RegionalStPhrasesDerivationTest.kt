@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)

package com.generalk1ng.sokkuri

import com.generalk1ng.sokkuri.engine.Utf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dev-only regenerator/verifier for the pilot's derived dictionary
 * `STPhrases_GeneratedFromRegionalPhrases.txt`.
 *
 * Upstream generates this file by converting the keys of `HKPhrases.txt`
 * and `TWPhrases.txt` through t2s (see OpenCC
 * `data/scripts/generate_st_phrases_from_regional_phrases.py`). This test
 * reproduces that derivation by dogfooding Sokkuri's own T2S converter, so
 * the pilot needs no upstream build. tools:dictgen (milestone M1, step 3.4)
 * replaces this with the permanent Kotlin implementation.
 *
 * Runs only when `OPENCC_DIR` points at a reference clone:
 * - generates the expected content in memory and asserts it is
 *   **byte-identical** to the committed resource (when present);
 * - when `SOKKURI_REGEN_DIR` is set, also writes the file there (used once
 *   to produce the committed copy; harmless to re-run — output is
 *   deterministic).
 */
class RegionalStPhrasesDerivationTest {

    @Test
    fun derivedDictionaryMatchesUpstreamScriptSemantics() {
        val openccDir = System.getenv("OPENCC_DIR") ?: return
        val t2s = Sokkuri.create(
            Config.T2S,
            Options { includeTofuRiskDictionaries = true },
        )

        // convertedKey -> original keys, in first-seen order (HK then TW),
        // mirroring the script's collision collection.
        val collisions = LinkedHashMap<String, MutableList<String>>()
        for (name in listOf("HKPhrases.txt", "TWPhrases.txt")) {
            val text = File(openccDir, "data/dictionary/$name").readText()
            for (rawLine in text.split('\n')) {
                val line = rawLine.trimEnd('\r')
                if (line.isEmpty() || line[0] == '#') continue
                val key = line.substringBefore('\t')
                val converted = t2s.convert(key)
                if (codePointLength(converted) < 3) continue
                collisions.getOrPut(converted) { ArrayList() }.add(key)
            }
        }

        val conflicts = collisions.filterValues { it.toSet().size > 1 }
        assertTrue(
            conflicts.isEmpty(),
            "conflicting regional phrase projections: $conflicts",
        )

        val entries = collisions.entries
            .sortedWith { a, b -> Utf.compareByCodePoint(a.key, b.key) }
        val generated = buildString {
            append(HEADER)
            for ((key, originals) in entries) {
                append(key).append('\t').append(originals[0]).append('\n')
            }
        }

        System.getenv("SOKKURI_REGEN_DIR")?.let { outDir ->
            File(outDir, "STPhrases_GeneratedFromRegionalPhrases.txt")
                .writeText(generated)
        }

        val resource = javaClass.classLoader
            ?.getResourceAsStream("dictionary/STPhrases_GeneratedFromRegionalPhrases.txt")
        if (resource != null) {
            assertEquals(generated, resource.readBytes().decodeToString())
        }
    }

    private fun codePointLength(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            i += Utf.charCount(Utf.codePointAt(text, i))
            count += 1
        }
        return count
    }

    private companion object {
        // Byte-exact replica of the header emitted by upstream
        // generate_st_phrases_from_regional_phrases.py (standalone variant).
        // Note: the Format line contains a literal TAB, as upstream does.
        val HEADER: String = """
            # Open Chinese Convert (OpenCC) Dictionary
            # File: STPhrases_GeneratedFromRegionalPhrases.txt
            # Format: key	value(s) (values separated by spaces)
            # License: Apache-2.0 (see LICENSE)
            # Source: generated from HKPhrases.txt, TWPhrases.txt keys via t2s.json
            # Used in configs: s2hkp.json, s2twp.json
            #
            # This generated ST phrase dictionary preserves Simplified-input spans
            # before applying regional phrase vocabulary.

        """.trimIndent() + "\n"
    }
}
