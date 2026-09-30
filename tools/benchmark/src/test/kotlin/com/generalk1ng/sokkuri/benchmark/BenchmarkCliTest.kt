package com.generalk1ng.sokkuri.benchmark

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Acceptance for the benchmark CLI boundary (m2-benchmark.md §3): argument
 * parsing is pure, dispatch is explicit, every known subcommand is wired,
 * and an unknown subcommand is an argument error — an invocation can never
 * silently measure nothing.
 */
class BenchmarkCliTest {

    @Test
    fun bareInvocationPrintsUsageAndReturnsZero() {
        assertEquals(0, run(BenchmarkOptions(subcommand = null, args = emptyList())))
    }

    @Test
    fun parseArgsSplitsSubcommandFromTrailingArgs() {
        val options = parseArgs(arrayOf("convert", "--profile", "s2t", "--iterations", "5000"))
        assertEquals("convert", options.subcommand)
        assertEquals(listOf("--profile", "s2t", "--iterations", "5000"), options.args)
    }

    @Test
    fun parseArgsRejectsFlagLikeSubcommandToken() {
        assertFailsWith<BenchmarkException> { parseArgs(arrayOf("--profile", "s2t")) }
    }

    @Test
    fun parseArgsRejectsEmptyInvocation() {
        assertFailsWith<BenchmarkException> { parseArgs(emptyArray()) }
    }

    @Test
    fun allFourSubcommandsAreWiredAndMeasure() {
        // One invocation per subcommand through the dispatcher; each must
        // exit 0 and print at least one [bench] line of its own kind.
        // convert gets trimmed iterations so the smoke stays a smoke.
        val invocations = listOf(
            Triple("convert", "kind=convert", listOf("--profile", "s2t", "--iterations", "50")),
            Triple("create", "kind=create", emptyList()),
            Triple("decode", "kind=decode", emptyList()),
            Triple("inspect", "kind=inspect", emptyList()),
        )
        for ((subcommand, kind, args) in invocations) {
            val (code, out) = captureStdout { run(BenchmarkOptions(subcommand, args)) }
            assertEquals(0, code, "$subcommand did not exit 0")
            assertTrue(
                out.lines().any { it.startsWith("[bench] ") && it.contains(kind) },
                "$subcommand printed no $kind line: $out",
            )
        }
    }

    @Test
    fun unknownSubcommandIsAnError() {
        assertFailsWith<BenchmarkException> { run(BenchmarkOptions("frobnicate", emptyList())) }
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
