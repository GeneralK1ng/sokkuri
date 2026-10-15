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

import com.generalk1ng.sokkuri.Sokkuri
import com.generalk1ng.sokkuri.SokkuriConfig
import com.generalk1ng.sokkuri.SokkuriOptions
import java.io.File
import kotlin.system.exitProcess

/**
 * `tools:upstream-diff` — runs Sokkuri and a build of the OpenCC reference
 * clone side by side and reports every place where the two disagree. Two
 * subcommands:
 *
 * - `diff` (the default): converts and inspects one corpus with both, for
 *   every profile;
 * - `configs`: loads a catalog of config documents with both, comparing
 *   which they accept, how they fail, and what the accepted ones convert to
 *   ([ConfigCases]);
 * - `bench`: times upstream on tools:benchmark's sample texts, the
 *   reference point for Sokkuri's own numbers;
 * - `fuzz`: grows the committed corpus `diff` replays, guided by libFuzzer's
 *   coverage of upstream ([FuzzCorpus]).
 *
 * This is the only check in the project that compares against upstream
 * *execution* rather than against upstream source. The golden suite is ported
 * from upstream's own expectations, so it certifies the corpus upstream chose
 * to write down; this tool reaches inputs no one wrote a case for, which is
 * where the IDS skip divergence lived undetected through two releases.
 *
 * It is not wired into `check`: it needs a C++ toolchain and a cmake build of
 * the clone, neither of which a routine build should require.
 */
internal fun main(args: Array<String>) {
    val invocation = try {
        parseInvocation(args.toList())
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println(USAGE)
        exitProcess(2)
    }
    exitProcess(
        when (invocation.command) {
            Command.DIFF -> runDiff(invocation.options)
            Command.CONFIGS -> runConfigs(invocation.options)
            Command.BENCH -> runBench(invocation.options)
            Command.FUZZ -> runFuzz(invocation.options)
        },
    )
}

/** The `diff` subcommand; returns the process exit code. */
private fun runDiff(options: Options): Int {
    println("[diff] preparing upstream at ${options.openccDir}")
    val upstream = UpstreamBuild(options.openccDir, options.buildDir, options.rebuild).prepare()

    val corpus = Corpus.build(options.openccDir, options.inputs, options.seed, options.corpus)
    val tofu = if (options.includeTofu) "included" else "excluded"
    println("[diff] corpus: ${corpus.size} inputs, tofu-risk dictionaries $tofu")

    var conversions = Tally()
    var inspections = Tally()
    // Every ported profile, in the enum's stable order: a profile added to
    // SokkuriConfig is diffed without anyone having to list it here.
    for (config in SokkuriConfig.entries) {
        val stem = config.stem
        val converter = Sokkuri.create(
            config,
            SokkuriOptions { includeTofuRiskDictionaries = options.includeTofu },
        )

        val expected = upstream.convert(stem, corpus, options.includeTofu)
        conversions += compare(stem, corpus.size, options.maxSamples) { index ->
            val actual = converter.convert(corpus[index])
            if (actual == expected[index]) null else renderMismatch(stem, corpus[index], expected[index], actual)
        }

        // The structure behind the output: segmentation and every stage,
        // against upstream's own Converter::Inspect.
        val expectedInspections = upstream.inspect(stem, corpus, options.includeTofu)
        inspections += compare("$stem inspect", corpus.size, options.maxSamples) { index ->
            FlatInspection.of(expectedInspections[index])
                .firstDifference(FlatInspection.of(converter.inspect(corpus[index])))
                ?.let { renderInspectionMismatch(stem, corpus[index], it) }
        }
    }

    println()
    println("[diff] comparisons=${conversions.comparisons} mismatches=${conversions.mismatches}")
    println("[diff] inspections=${inspections.comparisons} inspectionMismatches=${inspections.mismatches}")
    val aligned = conversions.mismatches == 0 && inspections.mismatches == 0
    if (aligned) {
        println("[diff] ALIGNED: every input converted and inspected identically on both sides")
    } else {
        println("[diff] DIVERGED: Sokkuri and the reference build disagree on the inputs above")
    }
    return if (aligned) 0 else 1
}

/** Running totals of one kind of comparison across profiles. */
private data class Tally(val comparisons: Int = 0, val mismatches: Int = 0) {
    operator fun plus(other: Tally): Tally = Tally(comparisons + other.comparisons, mismatches + other.mismatches)
}

/**
 * Compares [count] inputs with [mismatchAt], which renders the mismatch at
 * an index or returns `null` when both sides agree, prints the profile's
 * verdict line and up to [maxSamples] mismatches, and returns the tally.
 */
private fun compare(label: String, count: Int, maxSamples: Int, mismatchAt: (Int) -> String?): Tally {
    var mismatches = 0
    val samples = mutableListOf<String>()
    for (index in 0 until count) {
        val mismatch = mismatchAt(index) ?: continue
        mismatches++
        if (samples.size < maxSamples) samples += mismatch
    }
    println("[diff] $label: $count inputs, " + if (mismatches == 0) "aligned" else "$mismatches MISMATCHED")
    samples.forEach(::println)
    return Tally(count, mismatches)
}

private fun renderInspectionMismatch(stem: String, input: String, difference: String): String =
    buildString {
        appendLine("[diff]   [$stem] input=${input.forDisplay()}")
        append("[diff]     ${difference.forDisplay().removeSurrounding("\"")}")
    }

private fun renderMismatch(stem: String, input: String, upstream: String, sokkuri: String): String =
    buildString {
        appendLine("[diff]   [$stem] input=${input.forDisplay()}")
        appendLine("[diff]     upstream=${upstream.forDisplay()}")
        append("[diff]     sokkuri =${sokkuri.forDisplay()}")
    }

/** Renders control characters and surrogates so failures stay readable. */
internal fun String.forDisplay(): String = buildString {
    append('"')
    for (ch in this@forDisplay) {
        when {
            ch == '\t' -> append("\\t")
            ch.code < 0x20 || ch.isSurrogate() -> {
                append("\\u")
                val hex = ch.code.toString(16).uppercase()
                repeat(4 - hex.length) { append('0') }
                append(hex)
            }
            else -> append(ch)
        }
    }
    append('"')
}

/** The subcommands; [DIFF] runs when none is named. */
internal enum class Command(val word: String, val flags: Set<String>) {
    DIFF(
        "diff",
        setOf(
            "--opencc-dir", "--opencc-build-dir", "--rebuild", "--inputs", "--seed", "--corpus", "--tofu",
            "--max-samples",
        ),
    ),
    CONFIGS("configs", setOf("--opencc-dir", "--opencc-build-dir", "--rebuild")),
    BENCH(
        "bench",
        setOf("--opencc-dir", "--opencc-build-dir", "--rebuild", "--tofu", "--profile", "--samples", "--iterations"),
    ),
    FUZZ("fuzz", setOf("--opencc-dir", "--opencc-build-dir", "--rebuild", "--seconds", "--fuzz-corpus")),
}

/** Options shared by the subcommands; each accepts only the flags it lists in [Command.flags]. */
internal data class Options(
    val openccDir: File,
    val buildDir: File,
    val inputs: Int,
    val seed: Long,
    val includeTofu: Boolean,
    val corpus: File?,
    val rebuild: Boolean,
    val maxSamples: Int,
    /** `bench`: a profile stem, or `all`. */
    val profile: String = "all",
    /** `bench`: sample ids, from tools/shared-resources/samples. */
    val samples: List<String> = listOf("medium", "long"),
    /** `bench`: measured iterations for every sample, overriding the per-sample defaults. */
    val iterations: Int? = null,
    /** `fuzz`: how long libFuzzer runs. */
    val seconds: Int = 300,
    /** `fuzz`: the committed corpus file to grow. */
    val fuzzCorpus: File? = null,
)

internal data class Invocation(val command: Command, val options: Options)

private const val USAGE = """
Usage: upstream-diff [diff|configs|bench|fuzz] [options]

  diff (default)            convert and inspect one corpus with both implementations
  configs                   load the config catalog with both implementations
  bench                     time upstream on the benchmark sample texts
  fuzz                      grow the replayed corpus with libFuzzer (needs a clang with libFuzzer)

  --opencc-dir <dir>        OpenCC clone root (default: <cwd>/OpenCC)
  --opencc-build-dir <dir>  cmake build of the clone (default: <cwd>/build/upstream-opencc);
                            configured and built on first run and whenever
                            the clone has moved to another commit
  --rebuild                 rebuild the clone and the harness
diff only:
  --inputs <n>              random inputs to generate (default: 20000)
  --seed <n>                random seed (default: 20261006)
  --corpus <file>           extra inputs, one per line
  --tofu                    include tofu-risk dictionaries on BOTH sides
  --max-samples <n>         mismatching inputs to print per profile (default: 5)
bench only:
  --profile <stem|all>      profiles to time (default: all)
  --samples <id,id,...>     sample texts to time (default: medium,long)
  --iterations <n>          measured iterations per sample (default: tools:benchmark's)
  --tofu                    include tofu-risk dictionaries
fuzz only:
  --seconds <n>             how long libFuzzer runs (default: 300)
  --fuzz-corpus <file>      corpus to grow (default: this tool's src/main/resources/fuzz-corpus.jsonl)
"""

internal fun parseInvocation(args: List<String>, cwd: File = File("").absoluteFile): Invocation {
    val named = Command.entries.firstOrNull { it.word == args.firstOrNull() }
    val command = named ?: Command.DIFF
    val flags = if (named != null) args.drop(1) else args

    var openccDir = File(cwd, "OpenCC")
    var buildDir: File? = null
    var inputs = 20000
    var seed = 20261006L
    var includeTofu = false
    var corpus: File? = null
    var rebuild = false
    var maxSamples = 5
    var profile = "all"
    var samples = listOf("medium", "long")
    var iterations: Int? = null
    var seconds = 300
    var fuzzCorpus: File? = null

    var index = 0
    fun value(flag: String): String {
        if (index + 1 >= flags.size) throw IllegalArgumentException("$flag requires a value")
        return flags[++index]
    }
    while (index < flags.size) {
        val arg = flags[index]
        if (arg == "-h" || arg == "--help") throw IllegalArgumentException("")
        if (arg !in command.flags) {
            throw IllegalArgumentException("unknown argument for ${command.word}: $arg")
        }
        when (arg) {
            "--opencc-dir" -> openccDir = File(value(arg))
            "--opencc-build-dir" -> buildDir = File(value(arg))
            "--inputs" -> inputs = value(arg).toIntOrNull()
                ?: throw IllegalArgumentException("--inputs needs an integer")
            "--seed" -> seed = value(arg).toLongOrNull()
                ?: throw IllegalArgumentException("--seed needs an integer")
            "--corpus" -> corpus = File(value(arg))
            "--max-samples" -> maxSamples = value(arg).toIntOrNull()
                ?: throw IllegalArgumentException("--max-samples needs an integer")
            "--tofu" -> includeTofu = true
            "--rebuild" -> rebuild = true
            "--profile" -> profile = value(arg).also {
                require(it == "all" || SokkuriConfig.fromStem(it) != null) { "unknown profile: $it" }
            }
            "--samples" -> samples = value(arg).split(',').map(String::trim).filter(String::isNotEmpty)
                .ifEmpty { throw IllegalArgumentException("--samples needs at least one id") }
            "--iterations" -> iterations = value(arg).toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("--iterations needs a positive integer")
            "--seconds" -> seconds = value(arg).toIntOrNull()?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("--seconds needs a positive integer")
            "--fuzz-corpus" -> fuzzCorpus = File(value(arg))
        }
        index++
    }
    return Invocation(
        command,
        Options(
            openccDir = openccDir,
            buildDir = buildDir ?: File(cwd, "build/upstream-opencc"),
            inputs = inputs,
            seed = seed,
            includeTofu = includeTofu,
            corpus = corpus,
            rebuild = rebuild,
            maxSamples = maxSamples,
            profile = profile,
            samples = samples,
            iterations = iterations,
            seconds = seconds,
            fuzzCorpus = fuzzCorpus ?: File(cwd, FuzzCorpus.SOURCE_PATH),
        ),
    )
}
