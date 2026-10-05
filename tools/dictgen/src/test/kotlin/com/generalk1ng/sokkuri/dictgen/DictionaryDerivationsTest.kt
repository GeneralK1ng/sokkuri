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

package com.generalk1ng.sokkuri.dictgen

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the derivation ports (m1-dictgen-sok §3.4): synthetic cases pin
 * the semantics line by line, and — when `OPENCC_DIR` points at a clone and
 * `python3` is available — the byte-diff acceptance (§3.4.3) compares the
 * Kotlin ports against the actual upstream script outputs.
 */
class DictionaryDerivationsTest {

    private fun lexicon(vararg lines: Pair<String, List<String>>): ParsedLexicon =
        ParsedLexicon(lines.map { LexiconEntry(it.first, it.second) }, emptyMap())

    // --- reverse (common.py::reverse_items) ---

    @Test
    fun reverseBuildsValueToKeysMultimapSortedByCodePoint() {
        val reversed = DictionaryDerivations.reverse(
            lexicon("a" to listOf("X", "Y"), "b" to listOf("Y")),
            "test.txt",
        )
        assertEquals(
            listOf(LexiconEntry("X", listOf("a")), LexiconEntry("Y", listOf("a", "b"))),
            reversed,
        )
    }

    @Test
    fun reverseHonorsPreferencesByMovingThePreferredKeyFirst() {
        val reversed = DictionaryDerivations.reverse(
            ParsedLexicon(
                listOf(
                    LexiconEntry("幺", listOf("么")),
                    LexiconEntry("麼", listOf("么")),
                ),
                mapOf("么" to "幺"),
            ),
            "test.txt",
        )
        assertEquals(LexiconEntry("么", listOf("幺", "麼")), reversed[0])
    }

    @Test
    fun reverseRejectsPreferencesWithoutMatchingMapping() {
        val error = assertFailsWith<DictgenException> {
            DictionaryDerivations.reverse(
                ParsedLexicon(listOf(LexiconEntry("a", listOf("X"))), mapOf("X" to "missing")),
                "in.txt",
            )
        }
        assertTrue("has no matching mapping" in error.message!!)
    }

    @Test
    fun reverseSortsByCodePointNotUtf16() {
        // U+20000 sorts after all BMP scalars in code-point order but before
        // some in UTF-16 order — pins the comparator choice.
        val reversed = DictionaryDerivations.reverse(
            lexicon("z" to listOf("一"), "y" to listOf("𠀀")),
            "test.txt",
        )
        assertEquals(listOf("一", "𠀀"), reversed.map { it.key })
    }

    // --- extract_tofu_risk ---

    @Test
    fun tofuRiskExtractionDropsSelfMappingAndKeepsOrder() {
        val extracted = DictionaryDerivations.extractTofuRisk(
            listOf(
                "# comment",
                "# @tofu-risk:",
                "后\t后 後",
                "normal\tentry",
                "# @tofu-risk: extra annotation text is ignored",
                "发\t發发",
            ),
            "TSCharacters.txt",
        )
        assertEquals(
            extracted,
            listOf(
                LexiconEntry("后", listOf("後")),
                LexiconEntry("发", listOf("發发")),
            ),
        )
    }

    @Test
    fun tofuRiskExtractionRejectsAnnotationWithoutMapping() {
        assertFailsWith<DictgenException> {
            DictionaryDerivations.extractTofuRisk(listOf("# @tofu-risk:"), "in.txt")
        }
        assertFailsWith<DictgenException> {
            DictionaryDerivations.extractTofuRisk(
                listOf("# @tofu-risk:", "# comment"),
                "in.txt",
            )
        }
    }

    // --- generate_st_phrases_from_regional_phrases ---

    @Test
    fun regionalStPhraseGenerationMatchesTheScriptShape() {
        val text = DictionaryDerivations.generateRegionalStPhrases(
            inputs = listOf(
                "HKPhrases.txt" to listOf(
                    LexiconEntry("鼠標器", listOf("滑鼠")),
                    LexiconEntry("牛", listOf("牛牛")), // converts to < 3 code points: skipped
                ),
                "TWPhrases.txt" to listOf(
                    LexiconEntry("軟體質", listOf("軟件")),
                ),
            ),
            convert = { it }, // fake t2s: identity
            outputFileName = "STPhrases_GeneratedFromRegionalPhrases.txt",
        )
        val expected = buildString {
            append("# Open Chinese Convert (OpenCC) Dictionary\n")
            append("# File: STPhrases_GeneratedFromRegionalPhrases.txt\n")
            append("# Format: key\tvalue(s) (values separated by spaces)\n")
            append("# License: Apache-2.0 (see LICENSE)\n")
            append("# Source: generated from HKPhrases.txt, TWPhrases.txt keys via t2s.json\n")
            append("# Used in configs: s2hkp.json, s2twp.json\n")
            append("#\n")
            append("# This generated ST phrase dictionary preserves Simplified-input spans\n")
            append("# before applying regional phrase vocabulary.\n")
            append("\n")
            append("軟體質\t軟體質\n")
            append("鼠標器\t鼠標器\n")
        }
        assertEquals(expected, text)
    }

    @Test
    fun regionalStPhraseGenerationRejectsConflictingProjections() {
        val error = assertFailsWith<DictgenException> {
            DictionaryDerivations.generateRegionalStPhrases(
                inputs = listOf(
                    "A.txt" to listOf(LexiconEntry("abc", listOf("x"))),
                    "B.txt" to listOf(LexiconEntry("abd", listOf("x"))),
                ),
                convert = { "same" },
                outputFileName = "out.txt",
            )
        }
        assertTrue("Conflicting regional phrase simplified projections" in error.message!!)
    }

    // --- §3.4.3 byte-diff against the actual upstream script outputs ---

    @Test
    fun portsMatchUpstreamScriptOutputsByteForByte() {
        val openccDir = System.getenv("OPENCC_DIR")?.let(::File)
            ?: return // maintainer-only acceptance; CI-friendly skip
        val python = shutilWhich("python3") ?: return
        val scripts = openccDir.resolve("data/scripts")
        val tmp = kotlin.io.path.createTempDirectory("sokkuri-dictgen").toFile()

        // reverse.py × 3 (the recipes of m1 §3.4)
        for (name in listOf("TWVariants", "HKVariants", "JPShinjitaiCharacters")) {
            val upstreamOut = tmp.resolve("$name.upstream.txt")
            runScript(
                python,
                scripts.resolve("reverse.py"),
                openccDir.resolve("data/dictionary/$name.txt"),
                upstreamOut,
            )
            val kotlinOut = dumpLexicon(
                DictionaryDerivations.reverse(
                    parseLexicon(
                        openccDir.resolve("data/dictionary/$name.txt").readText(),
                        "$name.txt",
                    ),
                    "$name.txt",
                ),
            )
            assertEquals(upstreamOut.readText(), kotlinOut, "reverse($name) drifts from reverse.py")
        }

        // extract_tofu_risk.py (feeds TSCharactersExt)
        val upstreamTofu = tmp.resolve("TSCharactersExt.upstream.txt")
        runScript(
            python,
            scripts.resolve("extract_tofu_risk.py"),
            openccDir.resolve("data/dictionary/TSCharacters.txt"),
            upstreamTofu,
        )
        val kotlinTofu = dumpLexicon(
            DictionaryDerivations.extractTofuRisk(
                openccDir.resolve("data/dictionary/TSCharacters.txt").readText().split('\n'),
                "TSCharacters.txt",
            ),
        )
        assertEquals(upstreamTofu.readText(), kotlinTofu, "extract_tofu_risk drifts")
    }

    private fun shutilWhich(binary: String): String? =
        runCatching {
            ProcessBuilder("which", binary).start()
                .also { it.waitFor(10, TimeUnit.SECONDS) }
                .inputStream.bufferedReader().readText().trim().ifEmpty { null }
        }.getOrNull()

    private fun runScript(python: String, script: File, input: File, output: File) {
        val process = ProcessBuilder(python, script.absolutePath, input.absolutePath, output.absolutePath)
            .redirectErrorStream(true)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        if (process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() != 0) {
            throw AssertionError("${script.name} failed: $stdout")
        }
    }
}
