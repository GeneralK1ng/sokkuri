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

import kotlinx.serialization.json.Json
import java.io.File

/**
 * The compiled OpenCC side of the comparison (`opencc_harness.cpp`), bound
 * to the clone's profile configs and the reference build's dictionaries.
 * One method per harness subcommand; the line protocol of each is
 * documented in the C++ source, which is the other half of this contract.
 */
internal class UpstreamHarness(
    private val binary: File,
    private val configDir: File,
    /** The reference build's compiled dictionaries (`.ocd2`). */
    val dataDir: File,
) {

    /** Converts each of [inputs] independently under profile [stem], one call per input. */
    fun convert(stem: String, inputs: List<String>, includeTofu: Boolean): List<String> =
        runPerLine(listOf("convert") + profileArguments(stem, includeTofu), inputs)

    /** Upstream's `Converter::Inspect` for each of [inputs] under profile [stem]. */
    fun inspect(stem: String, inputs: List<String>, includeTofu: Boolean): List<UpstreamInspection> =
        runPerLine(listOf("inspect") + profileArguments(stem, includeTofu), inputs)
            .map { json.decodeFromString(UpstreamInspection.serializer(), it) }

    /**
     * Loads each of [configFiles] with upstream's `NewFromString`, resolving
     * dictionaries against [dictDir] only, and converts every line of
     * [probesFile] with each config that loads.
     */
    fun configs(dictDir: File, includeTofu: Boolean, probesFile: File, configFiles: List<File>): List<ConfigOutcome> =
        runPerLine(
            listOf("config", dictDir.absolutePath, flag(includeTofu), probesFile.absolutePath),
            configFiles.map { it.absolutePath },
        ).map { json.decodeFromString(HarnessConfigAnswer.serializer(), it).toOutcome() }

    private fun profileArguments(stem: String, includeTofu: Boolean): List<String> =
        listOf(stem, configDir.absolutePath, dataDir.absolutePath, flag(includeTofu))

    /**
     * Runs a subcommand that answers every stdin line with exactly one
     * stdout line, and returns the answers in input order.
     */
    private fun runPerLine(arguments: List<String>, lines: List<String>): List<String> {
        val answers = run(arguments, lines)
        check(answers.size == lines.size) {
            "harness answered ${answers.size} lines for ${lines.size} inputs: ${arguments.joinToString(" ")}"
        }
        return answers
    }

    /**
     * Runs the harness with [lines] on stdin and returns its stdout lines.
     *
     * All three streams go through files rather than pipes: a harness that
     * fills its stdout pipe while this side is still writing stdin would
     * deadlock. Stderr is shown only when the harness fails; otherwise it
     * holds nothing but upstream's own chatter, such as the schema warnings
     * it prints while loading a config.
     */
    private fun run(arguments: List<String>, lines: List<String>): List<String> {
        val inputFile = File.createTempFile("upstream-harness-", ".in")
        val outputFile = File.createTempFile("upstream-harness-", ".out")
        val errorFile = File.createTempFile("upstream-harness-", ".err")
        try {
            inputFile.writeText(lines.joinToString(separator = "") { "$it\n" })
            val process = ProcessBuilder(listOf(binary.absolutePath) + arguments)
                .redirectInput(inputFile)
                .redirectOutput(outputFile)
                .redirectError(errorFile)
                .start()
            val exit = process.waitFor()
            check(exit == 0) {
                "harness exited $exit: ${arguments.joinToString(" ")}\n${errorFile.readText().trimEnd()}"
            }
            // Every answer ends with a newline, so the split leaves one empty
            // artifact after the last answer (and only that for no answers).
            return outputFile.readText().split("\n").dropLast(1)
        } finally {
            inputFile.delete()
            outputFile.delete()
            errorFile.delete()
        }
    }

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    private companion object {
        /** Strict on purpose: an unknown field means the protocol drifted. */
        val json = Json
    }
}
