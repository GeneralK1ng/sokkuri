package com.generalk1ng.sokkuri.benchmark

import kotlin.system.exitProcess

/**
 * `tools:benchmark` — the measuring instrument of the Sokkuri stack
 * (m2-benchmark.md): conversion throughput, assembly latency, dictionary
 * decode cost, and the inspect() overhead ratio, all through the consumer
 * path. The milestone's §3 is the normative spec for the subcommands and
 * the `[bench]` output format; §4 fixes the embedded sample set.
 *
 * This file is the CLI boundary and nothing else: argument parsing,
 * dispatch, and help text. Each subcommand implementation lives in its own
 * file and is wired into [run] in the milestone step that builds it —
 * until then a known subcommand exits 1 with a pointer to its step, so an
 * invocation can never silently measure nothing.
 */
@ConsistentCopyVisibility
internal data class BenchmarkOptions internal constructor(
    /** The subcommand name (`create`/`convert`/`decode`/`inspect`), or null for a bare invocation. */
    val subcommand: String?,
    /** Trailing arguments after the subcommand, verbatim, for the subcommand's own parser. */
    val args: List<String>,
)

/**
 * Parses `argv` into [BenchmarkOptions]: the first token is the
 * subcommand, everything after it belongs to the subcommand. Help is
 * handled by [main] before parsing, so this function stays pure and
 * testable.
 *
 * @throws BenchmarkException on an empty or flag-like subcommand token.
 */
internal fun parseArgs(argv: Array<String>): BenchmarkOptions {
    val head = argv.firstOrNull()
        ?: throw BenchmarkException("missing subcommand (see --help)")
    if (head.startsWith("--")) {
        throw BenchmarkException("unknown argument: $head (see --help)")
    }
    return BenchmarkOptions(head, argv.drop(1))
}

/**
 * Dispatches [options] to a subcommand.
 *
 * @return process exit code: 0 success, 1 unknown/unfinished subcommand.
 */
internal fun run(options: BenchmarkOptions): Int {
    val subcommand = options.subcommand ?: run {
        printUsage()
        return 0
    }
    return when (subcommand) {
        "convert" -> ConvertBenchmark.run(options.args)
        "create" -> notImplemented("create", step = 2)
        "decode" -> notImplemented("decode", step = 2)
        "inspect" -> notImplemented("inspect", step = 3)
        else -> throw BenchmarkException("unknown subcommand: '$subcommand' (see --help)")
    }
}

/** Prints the usage block to stdout. */
internal fun printUsage() {
    println(
        """
        Usage: benchmark <subcommand> [args]
          convert [--profile all|<stem>] [--iterations N]   conversion throughput per profile
          create [--profile <stem> ...]                     cold/warm assembly latency + retained heap
          decode [--opencc-dir <dir>]                       packaged .sok decode (+ text comparison)
          inspect [--profile <stem>]                        inspect() vs convert() overhead ratio

        Every subcommand prints grep-stable `[bench] key=value` lines.
        Spec: docs/milestones/m2-benchmark.md (§3 subcommands, §4 samples).
        """.trimIndent(),
    )
}

/** Exit code and message for a known subcommand whose step has not landed yet. */
private fun notImplemented(name: String, step: Int): Int {
    System.err.println("benchmark: '$name' lands in m2-benchmark.md step $step")
    return 1
}

/** CLI entry point: help and argument errors are handled here, not in [parseArgs]. */
fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
        printUsage()
        exitProcess(0)
    }
    val code = try {
        run(parseArgs(args))
    } catch (e: BenchmarkException) {
        System.err.println(e.message)
        1
    }
    exitProcess(code)
}
