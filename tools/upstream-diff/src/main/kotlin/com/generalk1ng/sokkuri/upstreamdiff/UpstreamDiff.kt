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
 * `tools:upstream-diff` — converts one corpus with a build of the OpenCC
 * reference clone and with Sokkuri, then reports every input where the two
 * disagree.
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
    val options = try {
        parseArgs(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println(USAGE)
        exitProcess(2)
    }

    val upstream = UpstreamBuild(options.openccDir, options.buildDir, options.rebuild)
    println("[diff] preparing upstream at ${options.openccDir}")
    upstream.prepare()

    val corpus = Corpus.build(options.openccDir, options.inputs, options.seed, options.corpus)
    val tofu = if (options.includeTofu) "included" else "excluded"
    println("[diff] corpus: ${corpus.size} inputs, tofu-risk dictionaries $tofu")

    var comparisons = 0
    var mismatches = 0
    for (stem in PROFILE_STEMS) {
        val config = SokkuriConfig.fromStem(stem)
            ?: error("no SokkuriConfig for the upstream stem '$stem'")
        val converter = Sokkuri.create(
            config,
            SokkuriOptions { includeTofuRiskDictionaries = options.includeTofu },
        )

        val expected = upstream.convert(stem, corpus, options.includeTofu)
        check(expected.size == corpus.size) {
            "upstream returned ${expected.size} lines for ${corpus.size} inputs on '$stem'"
        }

        var profileMismatches = 0
        val samples = mutableListOf<String>()
        for (index in corpus.indices) {
            comparisons++
            val actual = converter.convert(corpus[index])
            if (actual == expected[index]) continue
            profileMismatches++
            mismatches++
            if (samples.size < options.maxSamples) {
                samples += renderMismatch(stem, corpus[index], expected[index], actual)
            }
        }
        println(
            "[diff] $stem: ${corpus.size} inputs, " +
                if (profileMismatches == 0) "aligned" else "$profileMismatches MISMATCHED",
        )
        samples.forEach(::println)
    }

    println()
    println("[diff] comparisons=$comparisons mismatches=$mismatches")
    if (mismatches > 0) {
        println("[diff] DIVERGED: Sokkuri and the reference build disagree on the inputs above")
    } else {
        println("[diff] ALIGNED: every input converted identically on both sides")
    }
    exitProcess(if (mismatches == 0) 0 else 1)
}

private fun renderMismatch(stem: String, input: String, upstream: String, sokkuri: String): String =
    buildString {
        appendLine("[diff]   [$stem] input=${input.forDisplay()}")
        appendLine("[diff]     upstream=${upstream.forDisplay()}")
        append("[diff]     sokkuri =${sokkuri.forDisplay()}")
    }

/** Renders control characters and surrogates so failures stay readable. */
private fun String.forDisplay(): String = buildString {
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

private data class DiffOptions(
    val openccDir: File,
    val buildDir: File,
    val inputs: Int,
    val seed: Long,
    val includeTofu: Boolean,
    val corpus: File?,
    val rebuild: Boolean,
    val maxSamples: Int,
)

private const val USAGE = """
Usage: upstream-diff [options]

  --opencc-dir <dir>        OpenCC clone root (default: <cwd>/OpenCC)
  --opencc-build-dir <dir>  cmake build of the clone (default: <cwd>/build/upstream-opencc);
                            configured and built on first run
  --inputs <n>              random inputs to generate (default: 20000)
  --seed <n>                random seed (default: 20261006)
  --corpus <file>           extra inputs, one per line
  --tofu                    include tofu-risk dictionaries on BOTH sides
  --rebuild                 rebuild the clone and the harness
  --max-samples <n>         mismatching inputs to print per profile (default: 5)
"""

private fun parseArgs(args: Array<String>): DiffOptions {
    val cwd = File("").absoluteFile
    var openccDir = File(cwd, "OpenCC")
    var buildDir: File? = null
    var inputs = 20000
    var seed = 20261006L
    var includeTofu = false
    var corpus: File? = null
    var rebuild = false
    var maxSamples = 5

    var index = 0
    fun value(flag: String): String {
        if (index + 1 >= args.size) throw IllegalArgumentException("$flag requires a value")
        return args[++index]
    }
    while (index < args.size) {
        when (val arg = args[index]) {
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
            "-h", "--help" -> throw IllegalArgumentException("")
            else -> throw IllegalArgumentException("unknown argument: $arg")
        }
        index++
    }
    return DiffOptions(
        openccDir = openccDir,
        buildDir = buildDir ?: File(cwd, "build/upstream-opencc"),
        inputs = inputs,
        seed = seed,
        includeTofu = includeTofu,
        corpus = corpus,
        rebuild = rebuild,
        maxSamples = maxSamples,
    )
}
