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

import kotlin.system.exitProcess

/**
 * `tools:benchmark` — the measuring instrument of the Sokkuri stack:
 * conversion throughput, assembly latency, dictionary decode cost, and the
 * inspect() overhead ratio, all through the consumer path. The subcommands
 * and the `[bench]` output format are specified below; [Samples] fixes the
 * embedded sample set.
 *
 * This file is the CLI boundary and nothing else: argument parsing,
 * dispatch, and help text. Each subcommand implementation lives in its own
 * file and is wired into [run]; an unknown subcommand is an argument error,
 * so an invocation can never silently measure nothing.
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
        "create" -> CreateBenchmark.run(options.args)
        "decode" -> DecodeBenchmark.run(options.args)
        "inspect" -> InspectBenchmark.run(options.args)
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
        """.trimIndent(),
    )
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
