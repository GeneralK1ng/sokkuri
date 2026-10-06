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

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The create subcommand: argument handling and the
 * `[bench]` line shape. A real S2T converter is assembled; numbers are not
 * asserted, only the stable key=value format.
 */
class CreateBenchmarkTest {

    @Test
    fun helpPrintsUsageAndReturnsZero() {
        val (code, out) = captureStdout { CreateBenchmark.run(listOf("--help")) }
        assertEquals(0, code)
        assertTrue(out.contains("usage: create"), "usage line missing: $out")
    }

    @Test
    fun defaultProfileIsS2TAndEmitsOneStableLine() {
        val (code, out) = captureStdout { CreateBenchmark.run(emptyList()) }
        assertEquals(0, code)
        val line = out.lines().single { it.startsWith("[bench]") }
        assertTrue(
            Regex(
                """\[bench] kind=create profile=s2t coldMs=\d+ warmMedianMs=\d+ heapKiB≈-?\d+""",
            ).matches(line),
            "line shape drifted: $line",
        )
    }

    @Test
    fun multiStemProfileFlagConsumesFollowingTokens() {
        val (code, out) = captureStdout { CreateBenchmark.run(listOf("--profile", "s2t", "t2s")) }
        assertEquals(0, code)
        val lines = out.lines().filter { it.startsWith("[bench]") }
        assertEquals(listOf("s2t", "t2s"), lines.map { it.substringAfter("profile=").substringBefore(" ") })
    }

    @Test
    fun unknownStemAndEmptyProfileListAndUnknownFlagsAreErrors() {
        assertFailsWith<BenchmarkException> { CreateBenchmark.run(listOf("--profile", "nope")) }
        assertFailsWith<BenchmarkException> { CreateBenchmark.run(listOf("--profile")) }
        assertFailsWith<BenchmarkException> { CreateBenchmark.run(listOf("--iterations", "10")) }
    }

    /** Runs [block] with stdout captured; returns the exit code and output. */
    private fun captureStdout(block: () -> Int): Pair<Int, String> {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer))
        return try {
            block() to buffer.toString()
        } finally {
            System.setOut(original)
        }
    }
}
