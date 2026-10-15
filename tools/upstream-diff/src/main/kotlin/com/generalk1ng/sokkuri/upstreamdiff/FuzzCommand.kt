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
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Longest input libFuzzer generates, in bytes: short inputs keep mismatch reports readable. */
private const val MAX_LENGTH = 96

/**
 * The `fuzz` subcommand: runs libFuzzer over the instrumented clone, seeded
 * with the hand-picked inputs and the committed corpus, minimizes what it
 * found, and appends the new inputs to the committed corpus that every
 * `diff` replays. Entries are never removed or reordered, so each run's
 * change is an append a reviewer can read. Returns the process exit code:
 * 1 when libFuzzer reports a crash in OpenCC.
 */
internal fun runFuzz(options: Options): Int {
    val corpusFile = checkNotNull(options.fuzzCorpus)
    println("[fuzz] preparing upstream at ${options.openccDir}")
    val upstream = UpstreamBuild(options.openccDir, options.buildDir, options.rebuild).prepare()
    val fuzzBuildDir = File(options.buildDir.parentFile, "${options.buildDir.name}-fuzz")
    val fuzzer = FuzzBuild(options.openccDir, fuzzBuildDir, options.rebuild).prepare()

    val workDir = File(options.buildDir.parentFile, "${options.buildDir.name}-fuzz-run")
    workDir.deleteRecursively()
    val seeds = File(workDir, "seeds").apply { mkdirs() }
    val found = File(workDir, "found").apply { mkdirs() }
    val merged = File(workDir, "merged").apply { mkdirs() }
    val artifacts = File(workDir, "artifacts").apply { mkdirs() }

    val existing = FuzzCorpus.read(corpusFile)
    val builtIn = Corpus.builtIn(options.openccDir)
    val known = (builtIn + existing).distinct()
    known.forEachIndexed { index, input -> File(seeds, "seed-$index").writeText(input) }
    val dictionary = File(workDir, "tokens.dict")
    dictionary.writeText(libFuzzerDictionary(Corpus.fuzzTokens(options.openccDir)))
    val environment = mapOf(
        "OPENCC_FUZZ_CONFIG_DIR" to upstream.configDir.absolutePath,
        "OPENCC_FUZZ_DATA_DIR" to upstream.dataDir.absolutePath,
        "OPENCC_FUZZ_PROFILES" to SokkuriConfig.entries.joinToString(",") { it.stem },
    )

    println("[fuzz] fuzzing for ${options.seconds}s from ${known.size} seeds")
    val exit = runProcess(
        listOf(
            fuzzer.absolutePath, found.path, seeds.path,
            "-max_total_time=${options.seconds}",
            "-max_len=$MAX_LENGTH",
            "-dict=${dictionary.path}",
            "-artifact_prefix=${artifacts.path}/",
            "-print_final_stats=1",
        ),
        environment,
    )
    if (exit != 0) {
        println("[fuzz] CRASH: libFuzzer exited $exit; the inputs that broke OpenCC are in $artifacts")
        return 1
    }
    // Keep only the inputs that add coverage, seeds included so a new input
    // must add something the hand-picked ones do not.
    runCommand(listOf(fuzzer.absolutePath, "-merge=1", merged.path, seeds.path, found.path), environment)

    val knownSet = known.toHashSet()
    val additions = merged.listFiles().orEmpty()
        .mapNotNull { decodeComparable(it.readBytes()) }
        .filter { it !in knownSet }
        .distinct()
        .sortedWith(compareBy<String> { it.length }.thenBy { it })
    corpusFile.parentFile?.mkdirs()
    corpusFile.writeText(FuzzCorpus.format(existing + additions))
    println("[fuzz] corpus: ${existing.size + additions.size} inputs, ${additions.size} new, in $corpusFile")
    return 0
}

/** [bytes] as text when they are well-formed UTF-8 the differential can use, else `null`. */
internal fun decodeComparable(bytes: ByteArray): String? {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        return null
    }
    return text.takeIf(Corpus::isComparable)
}

/**
 * libFuzzer's dictionary format: one quoted token per line, bytes outside
 * printable ASCII as `\xNN`, quote and backslash escaped.
 */
internal fun libFuzzerDictionary(tokens: List<String>): String = tokens.joinToString(separator = "") { token ->
    buildString {
        append('"')
        for (byte in token.encodeToByteArray()) {
            val value = byte.toInt() and 0xFF
            when {
                value == '"'.code || value == '\\'.code -> append('\\').append(value.toChar())
                value in 0x20..0x7E -> append(value.toChar())
                else -> append("\\x%02X".format(value))
            }
        }
        append("\"\n")
    }
}
