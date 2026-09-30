package com.generalk1ng.sokkuri.benchmark

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The inspect subcommand (m2-benchmark.md §3.5): argument handling, the
 * `[bench]` line shape, and the internal consistency of the reported ratio
 * (it must equal inspectUs / convertUs of the same line) — the format
 * contract is what keeps §8 numbers transcribable by hand.
 */
class InspectBenchmarkTest {

    @Test
    fun helpPrintsUsageAndReturnsZero() {
        val (code, out) = captureStdout { InspectBenchmark.run(listOf("--help")) }
        assertEquals(0, code)
        assertTrue(out.contains("usage: inspect"), "usage line missing: $out")
    }

    @Test
    fun defaultProfileIsS2TAndEmitsOneStableLine() {
        val (code, out) = captureStdout { InspectBenchmark.run(emptyList()) }
        assertEquals(0, code)
        val line = out.lines().single { it.startsWith("[bench]") }
        val fields = parseLine(line)

        assertEquals("s2t", fields["profile"])
        assertTrue(fields.containsKey("convertUs"), "convertUs missing: $line")
        assertTrue(fields.containsKey("inspectUs"), "inspectUs missing: $line")
        assertTrue(fields.containsKey("ratio"), "ratio missing: $line")
    }

    @Test
    fun reportedRatioMatchesTheTwoLatenciesOfTheSameLine() {
        val (_, out) = captureStdout { InspectBenchmark.run(emptyList()) }
        val line = out.lines().single { it.startsWith("[bench]") }
        val fields = parseLine(line)

        val convertUs = fields.getValue("convertUs").toDouble()
        val inspectUs = fields.getValue("inspectUs").toDouble()
        val ratio = fields.getValue("ratio").toDouble()

        // Two-decimal rounding on both latencies and the ratio: 0.01 is the
        // worst accumulated representation error of one line.
        assertTrue(
            kotlin.math.abs(ratio - inspectUs / convertUs) <= 0.01,
            "ratio drifted from inspectUs/convertUs: $line",
        )
    }

    @Test
    fun ratioStaysWithinAnOrderOfMagnitude() {
        // Sanity band, not an ordering claim: on a single-stage profile like
        // s2t the structural overhead of collecting Inspection segments is
        // sub-microsecond noise against a ~22µs conversion (a real run
        // measured ratio=0.98 — measurement order and JIT state dominate).
        // The band catches unit mix-ups (nanos printed as micros, swapped
        // fields) without asserting noise. The honest overhead answer is a
        // number in the acceptance record (m2-benchmark.md §8), not a
        // unit-test inequality.
        val (_, out) = captureStdout { InspectBenchmark.run(emptyList()) }
        val fields = parseLine(out.lines().single { it.startsWith("[bench]") })
        val ratio = fields.getValue("ratio").toDouble()

        assertTrue(ratio in 0.2..5.0, "ratio implausible for inspect vs convert: $out")
    }

    @Test
    fun explicitProfileIsEchoed() {
        val (code, out) = captureStdout { InspectBenchmark.run(listOf("--profile", "t2s")) }
        assertEquals(0, code)
        assertTrue(out.lines().any { it.startsWith("[bench] kind=inspect profile=t2s ") }, "t2s line missing: $out")
    }

    @Test
    fun unknownStemMissingValueAndUnknownFlagsAreErrors() {
        assertFailsWith<BenchmarkException> { InspectBenchmark.run(listOf("--profile", "nope")) }
        assertFailsWith<BenchmarkException> { InspectBenchmark.run(listOf("--profile")) }
        assertFailsWith<BenchmarkException> { InspectBenchmark.run(listOf("--iterations", "10")) }
    }

    /**
     * Parses a `[bench] key=value ...` line into its fields; fails the test
     * when a value is not exactly `key=value` shaped.
     */
    private fun parseLine(line: String): Map<String, String> =
        line.removePrefix("[bench] ").trim().split(" ").associate {
            val parts = it.split("=", limit = 2)
            assertEquals(2, parts.size, "malformed field '$it' in: $line")
            parts[0] to parts[1]
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
