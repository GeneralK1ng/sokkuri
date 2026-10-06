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

import com.generalk1ng.sokkuri.SokkuriConfig
import com.generalk1ng.sokkuri.Sokkuri
import java.util.*

/**
 * The `convert` subcommand: conversion throughput
 * per profile over the embedded sample set. One converter per profile,
 * created outside the timed region; dictionaries flow through the shared
 * process cache (constitution D3), so `--profile all` is dominated by the
 * first profile's cold decode, not sixteen of them.
 */
internal object ConvertBenchmark {

    private val BENCH_LINE_LOCALE: Locale = Locale.ROOT

    /**
     * Entry point of the subcommand; returns the process exit code.
     *
     * @throws BenchmarkException on unknown flags, unknown stems, or
     *   malformed iteration counts.
     */
    internal fun run(args: List<String>): Int {
        val options = parseArgs(args) ?: run {
            println("usage: convert [--profile all|<stem>] [--iterations N]")
            return 0
        }
        val profiles = resolveProfiles(options.profile)

        // Assembly is deliberately untimed: the D3 cache makes it near-free
        // after the first profile, and this subcommand measures conversion.
        val converters = profiles.associateWith { Sokkuri.create(it) }

        for (profile in profiles) {
            for (sample in Samples.forProfile(profile)) {
                val iterations = options.iterations ?: sample.defaultIterations
                val converter = converters.getValue(profile)
                val result = Timing.measure(iterations) { converter.convert(sample.text) }
                println(benchLine(profile, sample, result))
            }
        }
        return 0
    }

    /**
     * The grep-stable `[bench]` line. Numbers are formatted with
     * [Locale.ROOT]: a locale-sensitive decimal separator would break the
     * key=value contract in non-ROOT locales.
     */
    private fun benchLine(profile: SokkuriConfig, sample: Sample, result: Timing.Result): String =
        "[bench] kind=convert profile=%s sample=%s iterations=%d medianUs=%.2f p90Us=%.2f minUs=%.2f"
            .format(
                BENCH_LINE_LOCALE,
                profile.stem,
                sample.id,
                result.iterations,
                result.medianUs,
                result.p90Us,
                result.minUs,
            )

    /** `all` → every ported profile in enum order; otherwise the one stem. */
    private fun resolveProfiles(profile: String): List<SokkuriConfig> {
        if (profile == "all") return SokkuriConfig.entries.toList()
        val config = SokkuriConfig.fromStem(profile)
            ?: throw BenchmarkException("unknown profile: '$profile' (see --help)")
        return listOf(config)
    }

    @ConsistentCopyVisibility
    private data class Options(
        val profile: String,
        val iterations: Int?,
    )

    private fun parseArgs(args: List<String>): Options? {
        var profile = "all"
        var iterations: Int? = null
        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--profile" -> profile = requireValue(args, ++index, "--profile")
                "--iterations" -> {
                    val raw = requireValue(args, ++index, "--iterations")
                    iterations = raw.toIntOrNull()
                        ?: throw BenchmarkException("--iterations expects a positive integer, was '$raw'")
                    if (iterations <= 0) throw BenchmarkException("--iterations must be > 0, was $iterations")
                }

                "--help", "-h" -> return null
                else -> throw BenchmarkException("unknown argument: ${args[index]} (see --help)")
            }
            index += 1
        }
        return Options(profile, iterations)
    }

    /** Value half of a `--flag value` pair, with a clear error when absent. */
    private fun requireValue(args: List<String>, index: Int, flag: String): String {
        if (index >= args.size) throw BenchmarkException("$flag requires a value")
        return args[index]
    }
}
