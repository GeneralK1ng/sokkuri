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

import com.generalk1ng.sokkuri.resource.SokDictionary
import com.generalk1ng.sokkuri.resource.SokDictionaryFormat
import org.junit.AssumptionViolatedException
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every packaged dictionary gets the same verdict and the same canonical
 * digest from the runtime decoder as from the independent Rust reader
 * (docs/design/sok-rs.md §6.1), and every stored key is found again by the
 * runtime lookups.
 *
 * The expectations come from `sokcheck expect --packaged`, through the file
 * `SOKKURI_SOK_EXPECT` names. `./gradlew :sokkuri-runtime:sokConformanceTest`
 * generates the file and sets the variable, so it never skips; anywhere else
 * the test is reported as skipped, never as passed. The variable is not a
 * task input: run jvmTest with `--rerun` when setting it by hand.
 */
class PackagedSokConformanceTest {

    @Test
    fun packagedDictionariesMatchTheRustReader() {
        val path = System.getenv(VARIABLE)
            ?: throw AssumptionViolatedException("$VARIABLE is not set; run :sokkuri-runtime:sokConformanceTest")
        val file = File(path)
        assertTrue(file.isFile, "$VARIABLE names $path, which is not a file")
        val expected = SokConformance.parseExpectations(file.readText()).associateBy { it.name }

        val packaged = File(PACKAGED).listFiles { candidate -> candidate.name.endsWith(".sok") }
            .orEmpty().map { it.name }.sorted()
        assertTrue(packaged.isNotEmpty(), "no packaged dictionary under $PACKAGED")
        assertEquals(packaged, expected.keys.sorted(), "the expectations cover exactly the packaged dictionaries")

        for (name in packaged) {
            val bytes = assertNotNull(readTestResource("dictionary/$name"), "dictionary/$name")
            val expectation = expected.getValue(name)
            assertEquals(expectation.verdict, SokConformance.judge(bytes), name)
            assertTrue(expectation.auditClean, "$name: the Rust reader's audit found the packaged dictionary unclean")
            val dictionary = SokDictionaryFormat.decode(bytes) as SokDictionary
            SokConformance.exercise(dictionary, auditClean = true, where = name)
        }
    }

    /**
     * Not gated: the Kotlin digest of the encoder-written fixtures equals the
     * values an independent Python reading of the stream produced, the same
     * values the Rust tests pin (tools/sok-rs/sok/tests/digests.rs).
     */
    @Test
    fun fixtureDigestsMatchTheIndependentlyComputedValues() {
        val fixtures = File("../tools/sok-rs/fixtures")
        val pinned = mapOf(
            "empty.sok" to "a8c7f832281a39c5",
            "many-candidates.sok" to "485b319d0ddcff85",
            "prefixes.sok" to "d48af38172a39280",
            "widths.sok" to "257f553a9f91686e",
        )
        for ((name, digest) in pinned) {
            val bytes = fixtures.resolve(name).readBytes()
            val dictionary = SokDictionaryFormat.decode(bytes) as SokDictionary
            assertEquals(digest, SokConformance.hex(SokConformance.digest(dictionary)), name)
        }
    }

    private companion object {
        const val VARIABLE = "SOKKURI_SOK_EXPECT"

        /** The packaged dictionaries as files; tests run with the module directory as working directory. */
        const val PACKAGED = "src/commonMain/resources/dictionary"
    }
}
