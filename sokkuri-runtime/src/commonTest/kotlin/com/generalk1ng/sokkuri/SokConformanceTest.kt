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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The runtime decoder replays the conformance corpus (docs/design/sok-rs.md
 * §6.2) on every platform: the inputs the fuzzer kept against the
 * independent Rust reader, the hand-made cases of its tests that each break
 * one acceptance rule (§7), and the verdicts that reader wrote for them all.
 *
 * For every input the decoder must reach the same verdict, rejecting only by
 * [SokkuriException.InvalidFormat]; for every accepted one, the same digest,
 * and every key must survive the runtime lookups without an exception, which
 * is what an "accepted, then out of bounds on first access" defect breaks.
 * Where the Rust audit found the file clean, every key must also be found
 * again with its own candidates.
 *
 * The corpus grows with `./gradlew :tools:sok-rs:fuzz` (fuzzed inputs and
 * the seeds) or `importCorpusSeeds writeCorpusExpectations` (the seeds only),
 * both of which rewrite `expected.txt`; nothing here needs Rust.
 */
class SokConformanceTest {

    private fun text(path: String): String = assertNotNull(readTestResource(path), path).decodeToString()

    @Test
    fun theDecoderAgreesWithTheRustReaderOnTheConformanceCorpus() {
        val corpus = SokConformance.parseCorpus(text("sok-conformance/corpus.hex"))
        val expectations = SokConformance.parseExpectations(text("sok-conformance/expected.txt"))
        assertTrue(corpus.isNotEmpty(), "the conformance corpus is empty")
        assertEquals(corpus.keys.toList(), expectations.map { it.name }, "expected.txt follows corpus.hex line by line")

        var accepted = 0
        for (expectation in expectations) {
            val bytes = corpus.getValue(expectation.name)
            val verdict = SokConformance.judge(bytes)
            assertEquals(expectation.verdict, verdict, expectation.name)
            if (verdict is SokConformance.Verdict.Accept) {
                accepted += 1
                val dictionary = SokDictionaryFormat.decode(bytes) as SokDictionary
                SokConformance.exercise(dictionary, expectation.auditClean, expectation.name)
            }
        }
        assertTrue(accepted > 0, "the corpus holds accepted files, not only rejections")
        assertTrue(accepted < expectations.size, "the corpus holds rejected files, not only accepted ones")
    }

    @Test
    fun aCorpusLineThatDoesNotMatchItsIdIsRefused() {
        val line = "0000000000000000\t534f4b31"
        val error = runCatching { SokConformance.parseCorpus(line) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException && "does not match" in error.message.orEmpty(), "$error")
    }
}
