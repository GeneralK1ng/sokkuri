package com.generalk1ng.sokkuri.benchmark

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The convert subcommand (m2-benchmark.md §3.2): argument handling, and —
 * the step-1 acceptance — the grep-stable `[bench]` line format, asserted
 * with a tiny iteration count against a real conversion. Only the line
 * *shape* is pinned here; the numbers themselves are machine-dependent.
 */
class ConvertBenchmarkTest {

    @Test
    fun helpPrintsUsageAndReturnsZero() {
        val (code, out) = captureStdout { ConvertBenchmark.run(listOf("--help")) }
        assertEquals(0, code)
        assertTrue(out.contains("usage: convert"), "usage line missing: $out")
    }

    @Test
    fun unknownFlagAndUnknownProfileAndBadIterationsAreErrors() {
        assertFailsWith<BenchmarkException> { ConvertBenchmark.run(listOf("--frobnicate")) }
        assertFailsWith<BenchmarkException> { ConvertBenchmark.run(listOf("--profile", "nope", "--iterations", "10")) }
        assertFailsWith<BenchmarkException> { ConvertBenchmark.run(listOf("--iterations", "abc")) }
        assertFailsWith<BenchmarkException> { ConvertBenchmark.run(listOf("--iterations", "0")) }
    }

    @Test
    fun profileRunEmitsOneStableBenchLinePerSample() {
        // S2HK carries two samples (medium + hk); iterations forced small
        // so the assertion stays a smoke test, not a benchmark.
        val (code, out) = captureStdout {
            ConvertBenchmark.run(listOf("--profile", "s2hk", "--iterations", "30"))
        }
        assertEquals(0, code)
        val lines = out.lines().filter { it.startsWith("[bench]") }
        assertEquals(2, lines.size)
        val lineRe =
            Regex(
                """\[bench] kind=convert profile=s2hk sample=(medium|hk) iterations=30 """ +
                        """medianUs=\d+\.\d{2} p90Us=\d+\.\d{2} minUs=\d+\.\d{2}""",
            )
        for (line in lines) {
            assertTrue(lineRe.matches(line), "line shape drifted: $line")
        }
        assertEquals(listOf("medium", "hk"), lines.map { it.substringAfter("sample=").substringBefore(" ") })
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
