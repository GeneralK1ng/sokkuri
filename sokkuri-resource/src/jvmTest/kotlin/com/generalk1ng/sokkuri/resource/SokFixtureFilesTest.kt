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

@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)

package com.generalk1ng.sokkuri.resource

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the `.sok` fixtures of the independent Rust reader
 * (`tools/sok-rs/fixtures`, docs/verification/sok-reader.md) to this encoder: the
 * Rust side never encodes, so every valid file it reads must be the Kotlin
 * encoder's own output. Each fixture is compared byte for byte with a fresh
 * encoding, and a fixture with no definition here fails the test, so the
 * directory cannot drift from the encoder in either direction.
 *
 * With `SOKKURI_WRITE_SOK_FIXTURES=1` the test writes the fixtures instead
 * (run with `--rerun`: the variable is not a task input).
 */
class SokFixtureFilesTest {

    @Test
    fun fixturesAreTheEncodersOutput() {
        val directory = fixtureDirectory()
        if (System.getenv(WRITE_VARIABLE) == "1") {
            directory.mkdirs()
            directory.listFiles { file -> file.name.endsWith(".sok") }?.forEach { it.delete() }
            for ((name, entries) in FIXTURES) directory.resolve(name).writeBytes(SokDictionaryEncoder.encode(entries))
        }
        val committed = directory.listFiles { file -> file.name.endsWith(".sok") }.orEmpty()
            .map { it.name }
            .sorted()
        assertEquals(
            FIXTURES.keys.sorted(),
            committed,
            "fixture files in $directory (regenerate with $WRITE_VARIABLE=1)",
        )
        for ((name, entries) in FIXTURES) {
            assertContentEquals(
                SokDictionaryEncoder.encode(entries),
                directory.resolve(name).readBytes(),
                "$name is not the encoder's output (regenerate with $WRITE_VARIABLE=1 and --rerun)",
            )
        }
    }

    @Test
    fun everyFixtureDecodes() {
        for ((name, entries) in FIXTURES) {
            val dictionary = SokDictionaryFormat.decode(SokDictionaryEncoder.encode(entries))
            for ((key, candidates) in entries) {
                assertEquals(candidates, dictionary.matchExact(key)?.candidates, "$name: $key")
            }
        }
    }

    /** The repository's `tools/sok-rs/fixtures`; tests run with the module directory as working directory. */
    private fun fixtureDirectory(): File {
        val repository = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { it.resolve("settings.gradle.kts").isFile }
        assertTrue(repository != null, "no settings.gradle.kts above ${File("").absolutePath}")
        return repository.resolve("tools/sok-rs/fixtures")
    }

    private companion object {
        const val WRITE_VARIABLE = "SOKKURI_WRITE_SOK_FIXTURES"

        /**
         * Small dictionaries covering the format's structure: keys that prefix
         * one another, every UTF-8 width including a supplementary-plane key,
         * several candidates and a U+FFFD candidate, the empty dictionary, and
         * an entry at the 255-candidate limit.
         */
        val FIXTURES: Map<String, List<Pair<String, List<String>>>> = mapOf(
            "prefixes.sok" to listOf("a" to listOf("A"), "ab" to listOf("AB"), "abc" to listOf("ABC")),
            "widths.sok" to listOf(
                "z" to listOf("Z"),
                "é" to listOf("É", "e"),
                "中" to listOf("中", "衆"),
                "中文" to listOf("中文"),
                "�" to listOf("?"),
                "𠀀" to listOf("x", "y", "z"),
                "𠀀中" to listOf("�"),
            ),
            "empty.sok" to emptyList(),
            "many-candidates.sok" to listOf("k" to List(255) { index -> ('a' + index % 26).toString() }),
        )
    }
}
