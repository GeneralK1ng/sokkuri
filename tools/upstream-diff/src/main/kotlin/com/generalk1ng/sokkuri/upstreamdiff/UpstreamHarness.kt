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
    private val dataDir: File,
) {

    /** Converts each of [inputs] independently under profile [stem], one call per input. */
    fun convert(stem: String, inputs: List<String>, includeTofu: Boolean): List<String> =
        runPerLine(listOf("convert") + profileArguments(stem, includeTofu), inputs)

    /** Upstream's `Converter::Inspect` for each of [inputs] under profile [stem]. */
    fun inspect(stem: String, inputs: List<String>, includeTofu: Boolean): List<UpstreamInspection> =
        runPerLine(listOf("inspect") + profileArguments(stem, includeTofu), inputs)
            .map { json.decodeFromString(UpstreamInspection.serializer(), it) }

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
     * Both streams go through files rather than pipes: a harness that fills
     * its stdout pipe while this side is still writing stdin would deadlock.
     */
    private fun run(arguments: List<String>, lines: List<String>): List<String> {
        val inputFile = File.createTempFile("upstream-harness-", ".in")
        val outputFile = File.createTempFile("upstream-harness-", ".out")
        try {
            inputFile.writeText(lines.joinToString(separator = "") { "$it\n" })
            val process = ProcessBuilder(listOf(binary.absolutePath) + arguments)
                .redirectInput(inputFile)
                .redirectOutput(outputFile)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val exit = process.waitFor()
            check(exit == 0) { "harness exited $exit: ${arguments.joinToString(" ")}" }
            // Every answer ends with a newline, so the split leaves one empty
            // artifact after the last answer (and only that for no answers).
            return outputFile.readText().split("\n").dropLast(1)
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
    }

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    private companion object {
        /** Strict on purpose: an unknown field means the protocol drifted. */
        val json = Json
    }
}
