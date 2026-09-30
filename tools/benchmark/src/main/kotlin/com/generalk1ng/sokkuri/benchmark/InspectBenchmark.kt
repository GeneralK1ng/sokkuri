package com.generalk1ng.sokkuri.benchmark

import com.generalk1ng.sokkuri.Config
import com.generalk1ng.sokkuri.Sokkuri
import java.util.*

/**
 * The `inspect` subcommand (m2-benchmark.md §3.5): the cost of the
 * Inspection structure that [Sokkuri.inspect] collects on top of a plain
 * [Sokkuri.convert] of the same input. The two are measured back to back on
 * one converter with the shared §3.1 methodology; the reported ratio is the
 * price of the differentiating feature, in multiples of a plain conversion.
 *
 * The input is fixed to the `medium` pilot probe (m2-benchmark.md §4): it is
 * the sample every profile converts, so ratios stay comparable across
 * profiles, and its convert number is directly comparable with the convert
 * subcommand's.
 */
internal object InspectBenchmark {

    private val BENCH_LINE_LOCALE: Locale = Locale.ROOT

    /**
     * Entry point of the subcommand; returns the process exit code.
     *
     * @throws BenchmarkException on unknown flags or an unknown stem.
     */
    internal fun run(args: List<String>): Int {
        val profile = parseArgs(args) ?: run {
            return 0
        }

        // Assembly is deliberately untimed; see ConvertBenchmark.
        val converter = Sokkuri.create(profile)
        val text = Samples.MEDIUM.text
        val iterations = Samples.MEDIUM.defaultIterations

        // Both ops share the JIT state of this process, but the second
        // measurement still gets its own §3.1 warmup so the comparison is
        // methodology-identical, not "convert measured cold-er than inspect".
        val convert = Timing.measure(iterations) { converter.convert(text) }
        val inspect = Timing.measure(iterations) { converter.inspect(text) }

        println(benchLine(profile, convert.medianUs, inspect.medianUs))
        return 0
    }

    /**
     * The grep-stable `[bench]` line of §3.5: both latencies as µs/op and
     * their ratio (1.00 = inspection is free, 2.00 = it doubles the cost).
     * Numbers are formatted with [Locale.ROOT]; see ConvertBenchmark.
     */
    private fun benchLine(profile: Config, convertUs: Double, inspectUs: Double): String =
        "[bench] kind=inspect profile=%s convertUs=%.2f inspectUs=%.2f ratio=%.2f"
            .format(BENCH_LINE_LOCALE, profile.stem, convertUs, inspectUs, inspectUs / convertUs)

    /**
     * @return the requested profile (default `s2t`), or null on `--help`.
     * @throws BenchmarkException on unknown flags or an unknown stem.
     */
    private fun parseArgs(args: List<String>): Config? {
        var stem: String? = null
        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--profile" -> {
                    if (index + 1 >= args.size) throw BenchmarkException("--profile requires a value")
                    stem = args[index + 1]
                    index += 1
                }

                "--help", "-h" -> return null
                else -> throw BenchmarkException("unknown argument: ${args[index]} (see --help)")
            }
            index += 1
        }
        val resolved = stem ?: "s2t"
        return Config.fromStem(resolved)
            ?: throw BenchmarkException("unknown profile: '$resolved' (see --help)")
    }
}
