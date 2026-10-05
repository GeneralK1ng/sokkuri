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
 * The decode subcommand (m2-benchmark.md §3.4): the unconditional `.sok`
 * side runs without an OpenCC clone and emits stable lines; the text
 * comparison degrades to a stderr note (exit stays 0) when the clone is
 * absent or a file is missing — never a failure.
 */
class DecodeBenchmarkTest {

    @Test
    fun helpPrintsUsageAndReturnsZero() {
        val (code, out, _) = captureOutput { DecodeBenchmark.run(listOf("--help")) }
        assertEquals(0, code)
        assertTrue(out.contains("usage: decode"), "usage line missing: $out")
    }

    @Test
    fun sokDecodeRunsWithoutCloneAndSkipsTextComparison() {
        val (code, out, err) = captureOutput { DecodeBenchmark.run(emptyList()) }
        assertEquals(0, code)
        val lines = out.lines().filter { it.startsWith("[bench]") }
        assertEquals(3, lines.size)
        for ((line, name) in lines.zip(listOf("STCharacters", "TSCharacters", "STPhrases"))) {
            assertTrue(
                Regex(
                    """\[bench] kind=decode dict=$name sokBytes=\d+ """ +
                            """sokMedianMs=\d+ sokHeapKiB≈-?\d+""",
                ).matches(line),
                "line shape drifted: $line",
            )
            assertTrue(!line.contains("textMedianMs"), "text fields without a clone: $line")
        }
        assertTrue(err.contains("text comparison skipped"), "skip note missing: $err")
    }

    @Test
    fun missingCloneDirectorySkipsComparisonButStillExitZero() {
        val (code, out, err) = captureOutput {
            DecodeBenchmark.run(listOf("--opencc-dir", "/nonexistent/opencc"))
        }
        assertEquals(0, code)
        assertEquals(3, out.lines().filter { it.startsWith("[bench]") }.size)
        assertTrue(err.contains("text comparison skipped"))
    }

    @Test
    fun unknownFlagAndValuelessOpenccDirAreErrors() {
        assertFailsWith<BenchmarkException> { DecodeBenchmark.run(listOf("--profile", "s2t")) }
        assertFailsWith<BenchmarkException> { DecodeBenchmark.run(listOf("--opencc-dir")) }
    }

    /** Runs [block] capturing stdout and stderr; returns code, out, err. */
    private fun captureOutput(block: () -> Int): Triple<Int, String, String> {
        val originalOut = System.out
        val originalErr = System.err
        val outBuffer = ByteArrayOutputStream()
        val errBuffer = ByteArrayOutputStream()
        System.setOut(PrintStream(outBuffer))
        System.setErr(PrintStream(errBuffer))
        return try {
            Triple(block(), outBuffer.toString(), errBuffer.toString())
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }
}
