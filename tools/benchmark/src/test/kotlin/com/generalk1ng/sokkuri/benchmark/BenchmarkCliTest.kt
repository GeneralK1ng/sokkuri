package com.generalk1ng.sokkuri.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Step-0 acceptance for the benchmark CLI skeleton (m2-benchmark.md §0):
 * argument parsing is pure, dispatch is explicit, and no invocation can
 * silently measure nothing — known-but-unimplemented subcommands exit 1
 * with a pointer to their milestone step.
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
    fun knownSubcommandsExitOneWithStepPointerUntilTheirStepLands() {
        assertEquals(1, run(BenchmarkOptions("convert", emptyList())))
        assertEquals(1, run(BenchmarkOptions("create", emptyList())))
        assertEquals(1, run(BenchmarkOptions("decode", emptyList())))
        assertEquals(1, run(BenchmarkOptions("inspect", emptyList())))
    }

    @Test
    fun unknownSubcommandIsAnError() {
        assertFailsWith<BenchmarkException> { run(BenchmarkOptions("frobnicate", emptyList())) }
    }
}
