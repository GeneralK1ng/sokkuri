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

/**
 * The `create` subcommand: assembly latency and
 * retained heap per profile.
 *
 * Cold/warm honesty note: all profiles of one run share a process, and the
 * shared dictionary cache (constitution D3) is process-wide — so only the
 * first profile's `coldMs` is a true cold start; later profiles measure
 * "first create of this profile with dictionaries already cached", which
 * is exactly the product behavior a consumer sees when creating several
 * converters in one app. Run single-profile processes for isolated cold
 * numbers.
 */
internal object CreateBenchmark {

    private const val WARM_RUNS: Int = 5

    /**
     * Entry point of the subcommand; returns the process exit code.
     *
     * @throws BenchmarkException on unknown flags or unknown stems.
     */
    internal fun run(args: List<String>): Int {
        val profiles = parseArgs(args) ?: run {
            println("usage: create [--profile <stem> ...]")
            return 0
        }
        for (profile in profiles) {
            System.gc()
            val coldStart = System.nanoTime()
            Sokkuri.create(profile)
            val coldMs = (System.nanoTime() - coldStart) / 1_000_000

            System.gc()
            val heapBefore = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            val kept = Sokkuri.create(profile)
            System.gc()
            val heapKiB = (Runtime.getRuntime()
                .let { it.totalMemory() - it.freeMemory() } - heapBefore) / 1024
            kept.hashCode() // keep live until after the heap reading

            val warmMillis = (1..WARM_RUNS).map {
                val start = System.nanoTime()
                Sokkuri.create(profile)
                (System.nanoTime() - start) / 1_000_000
            }.sorted()

            println(
                "[bench] kind=create profile=${profile.stem} coldMs=$coldMs " +
                        "warmMedianMs=${warmMillis[WARM_RUNS / 2]} heapKiB≈$heapKiB",
            )
        }
        return 0
    }

    /**
     * `--profile` consumes every following non-flag token, so multiple
     * stems sit on one flag: `--profile s2t t2s`. Absent entirely, the
     * default is `s2t`.
     *
     * @return the requested profiles, or null on `--help`.
     * @throws BenchmarkException on unknown flags, an empty `--profile`
     *   list, or unknown stems.
     */
    private fun parseArgs(args: List<String>): List<SokkuriConfig>? {
        var rawProfiles: List<String>? = null
        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--profile" -> {
                    val stems = ArrayList<String>()
                    index++
                    while (index < args.size && !args[index].startsWith("--")) {
                        stems.add(args[index])
                        index++
                    }
                    if (stems.isEmpty()) throw BenchmarkException("--profile requires at least one stem")
                    rawProfiles = stems
                    continue
                }

                "--help", "-h" -> return null
                else -> throw BenchmarkException("unknown argument: ${args[index]} (see --help)")
            }
            index++
        }
        val stems = rawProfiles ?: listOf("s2t")
        return stems.map { stem ->
            SokkuriConfig.fromStem(stem) ?: throw BenchmarkException("unknown profile: '$stem' (see --help)")
        }
    }
}
