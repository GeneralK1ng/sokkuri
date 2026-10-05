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

@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)

package com.generalk1ng.sokkuri.benchmark

import com.generalk1ng.sokkuri.resource.SokDictionaryFormat
import com.generalk1ng.sokkuri.resource.TextDictionaryFormat
import java.io.File

/**
 * The `decode` subcommand (m2-benchmark.md §3.4): dictionary decode cost
 * for the packaged `.sok` files — the M1 §5.1 design gate, re-measurable
 * on demand.
 *
 * The `.sok` side is unconditional (bytes come from the packaged
 * resources on the class path). The text side — `TextDictionaryFormat`
 * over the upstream `.txt`, the comparison half of the gate — needs an
 * OpenCC clone (the pilot text resources were replaced by `.sok` in M1),
 * so it is opt-in via `--opencc-dir` and degrades to a stderr note when
 * the directory or a dictionary file is absent. The `.sok` heap figure is
 * the *marginal* retention: zero-copy decode retains the (already loaded)
 * byte buffer; the total footprint is the file size, printed alongside.
 */
internal object DecodeBenchmark {

    private const val REPETITIONS: Int = 7

    /** Dictionaries representative of small/mid/large tables (M1 §5.1 set). */
    private val DICTIONARIES: List<String> = listOf("STCharacters", "TSCharacters", "STPhrases")

    /**
     * Entry point of the subcommand; returns the process exit code.
     *
     * @throws BenchmarkException only on malformed flags; a missing clone
     *   or text file is a skip (stderr note), never a failure.
     */
    internal fun run(args: List<String>): Int {
        val options = parseArgs(args) ?: run {
            println("usage: decode [--opencc-dir <dir>]")
            return 0
        }
        val openccDir = options.openccDir

        val loader = object {}.javaClass.classLoader
            ?: throw BenchmarkException("no class loader available to read the packaged dictionaries")

        for (name in DICTIONARIES) {
            val sokBytes = loader.getResourceAsStream("dictionary/$name.sok")
                ?.use { it.readBytes() }
                ?: throw BenchmarkException("packaged dictionary/$name.sok missing from the resources")

            val sokMedianMs = Timing.medianMillis(REPETITIONS) { SokDictionaryFormat.decode(sokBytes) }
            val sokHeapKiB = Timing.retainedHeapKiB { SokDictionaryFormat.decode(sokBytes) }

            val textBytes = openccDir?.let { File(it, "data/dictionary/$name.txt") }
            if (textBytes != null && textBytes.isFile) {
                val bytes = textBytes.readBytes()
                val textMedianMs = Timing.medianMillis(REPETITIONS) { TextDictionaryFormat.decode(bytes) }
                val textHeapKiB = Timing.retainedHeapKiB { TextDictionaryFormat.decode(bytes) }
                println(
                    "[bench] kind=decode dict=$name sokBytes=${sokBytes.size} textBytes=${bytes.size} " +
                            "sokMedianMs=$sokMedianMs sokHeapKiB≈$sokHeapKiB " +
                            "textMedianMs=$textMedianMs textHeapKiB≈$textHeapKiB",
                )
            } else {
                if (openccDir != null) {
                    System.err.println("benchmark: $name.txt not found under $openccDir, text comparison skipped")
                } else {
                    System.err.println("benchmark: no --opencc-dir, text comparison skipped")
                }
                println(
                    "[bench] kind=decode dict=$name sokBytes=${sokBytes.size} " +
                            "sokMedianMs=$sokMedianMs sokHeapKiB≈$sokHeapKiB",
                )
            }
        }
        return 0
    }

    private data class Options(val openccDir: String?)

    /**
     * @return the parsed options, or null on `--help`.
     * @throws BenchmarkException when `--opencc-dir` has no value.
     */
    private fun parseArgs(args: List<String>): Options? {
        var openccDir: String? = null
        var index = 0
        while (index < args.size) {
            when (args[index]) {
                "--opencc-dir" -> {
                    if (index + 1 >= args.size) throw BenchmarkException("--opencc-dir requires a value")
                    openccDir = args[index + 1]
                    index += 1
                }

                "--help", "-h" -> return null
                else -> throw BenchmarkException("unknown argument: ${args[index]} (see --help)")
            }
            index++
        }
        return Options(openccDir)
    }
}
