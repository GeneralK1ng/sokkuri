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

@file:OptIn(SokkuriInternalApi::class)

package com.generalk1ng.sokkuri

import com.generalk1ng.sokkuri.engine.Utf
import com.generalk1ng.sokkuri.resource.SokDictionaryEncoder
import java.io.File
import kotlin.test.*

/**
 * Dev-only verifier for the packaged derived dictionary
 * `dictionary/STPhrases_GeneratedFromRegionalPhrases.sok`.
 *
 * Upstream generates this lexicon by converting the keys of `HKPhrases.txt`
 * and `TWPhrases.txt` through t2s (OpenCC
 * `data/scripts/generate_st_phrases_from_regional_phrases.py`). This test
 * reproduces that derivation by dogfooding Sokkuri's own T2S converter and
 * asserts the packaged `.sok` is **byte-identical** to a fresh encoding of
 * it — guarding against a stale packaged artifact whenever the clone's
 * regional phrase tables or the derivation chain change.
 *
 * `tools:dictgen` (milestone M1) is the permanent generator; this test is
 * the runtime-side tripwire that its products are current. Runs only when
 * `OPENCC_DIR` points at a reference clone.
 */
class RegionalStPhrasesDerivationTest {

    @Test
    fun packagedDerivedDictionaryMatchesFreshDerivationByteForByte() {
        val openccDir = System.getenv("OPENCC_DIR") ?: return
        val t2s = Sokkuri.create(
            SokkuriConfig.T2S,
            SokkuriOptions { includeTofuRiskDictionaries = true },
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
            .map { (converted, originals) -> converted to listOf(originals[0]) }

        // The encoder is deterministic (m1-dictgen-sok DoD 4), so identical
        // lexicons imply identical bytes; any drift in the derivation chain
        // or the packaged artifact shows up as a byte mismatch.
        val fresh = SokDictionaryEncoder.encode(entries)
        val packaged = javaClass.classLoader
            ?.getResourceAsStream("dictionary/STPhrases_GeneratedFromRegionalPhrases.sok")
            ?.use { it.readBytes() }
        assertNotNull(packaged, "packaged STPhrases_GeneratedFromRegionalPhrases.sok missing")
        assertContentEquals(fresh, packaged)

        // Decode-side smoke check: the packaged dictionary must answer every
        // derived key with its original regional phrase as the candidate.
        val decoded = com.generalk1ng.sokkuri.resource.SokDictionaryFormat.decode(packaged)
        for ((converted, originals) in entries) {
            val entry = decoded.matchExact(converted)
            assertNotNull(entry, "packaged dictionary lost key '$converted'")
            assertEquals(listOf(originals[0]), entry.candidates, "candidate drift for '$converted'")
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
}
