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

package com.generalk1ng.sokkuri.upstreamdiff

import com.generalk1ng.sokkuri.SokkuriConfig

/**
 * Measured iterations per sample, mirroring the defaults of tools:benchmark's
 * `Sample` so a reference timing of upstream does the same work as the
 * Sokkuri timing it is set beside. Change both together.
 */
internal val BENCH_ITERATIONS: Map<String, Int> = mapOf(
    "medium" to 20_000,
    "short" to 20_000,
    "twp" to 20_000,
    "hk" to 20_000,
    "phrases" to 5_000,
    "long" to 2_000,
)

/**
 * The `bench` subcommand: times upstream converting tools:benchmark's sample
 * texts, one line per profile and sample in the shape of tools:benchmark's
 * own `kind=convert` lines, with `kind=upstream`. Returns the process exit
 * code.
 *
 * The two sides run in different processes and languages, so pair the lines
 * from one machine and one sitting; the numbers say how Sokkuri compares to
 * the C++ core on that machine, not how fast either is in general.
 */
internal fun runBench(options: Options): Int {
    val texts = options.samples.associateWith { id ->
        sampleTextOrNull(id) ?: run {
            System.err.println("unknown sample: $id (tools/shared-resources/samples)")
            return 2
        }
    }
    val profiles = if (options.profile == "all") {
        SokkuriConfig.entries
    } else {
        listOf(checkNotNull(SokkuriConfig.fromStem(options.profile)))
    }

    println("[upstream] preparing upstream at ${options.openccDir}")
    val upstream = UpstreamBuild(options.openccDir, options.buildDir, options.rebuild).prepare()
    println("[upstream] tofu-risk dictionaries ${if (options.includeTofu) "included" else "excluded"}")
    for (profile in profiles) {
        for ((id, text) in texts) {
            val iterations = options.iterations ?: BENCH_ITERATIONS[id] ?: BENCH_ITERATIONS.getValue("medium")
            val timing = upstream.bench(profile.stem, text, iterations, options.includeTofu)
            println(benchLine(profile.stem, id, timing))
        }
    }
    return 0
}

/** One result line, matching tools:benchmark's `[bench]` line shape. */
internal fun benchLine(stem: String, sample: String, timing: UpstreamTiming): String =
    "[bench] kind=upstream profile=%s sample=%s iterations=%d medianUs=%.2f p90Us=%.2f minUs=%.2f".format(
        stem,
        sample,
        timing.iterations,
        timing.medianUs,
        timing.p90Us,
        timing.minUs,
    )

/**
 * The text of sample [id] from the shared `samples/` resources, or `null`
 * when there is no such sample. Each file ends with one newline that is not
 * part of the sample, as tools:benchmark reads them.
 */
internal fun sampleTextOrNull(id: String): String? {
    val resource = object {}.javaClass.getResourceAsStream("/samples/$id.txt") ?: return null
    val text = resource.use { it.readBytes().decodeToString() }
    check(text.endsWith("\n")) { "samples/$id.txt must end with a newline" }
    return text.removeSuffix("\n")
}
