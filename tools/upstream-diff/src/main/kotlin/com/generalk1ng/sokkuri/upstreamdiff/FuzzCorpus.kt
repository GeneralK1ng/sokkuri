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

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The committed corpus `upstream-diff fuzz` grows and every `diff` run
 * replays: one JSON string per line, so control characters stay visible in
 * review and no input can break the line structure. Blank lines are
 * ignored. The file lives with the tool's resources
 * ([SOURCE_PATH], on the classpath as [RESOURCE]).
 */
internal object FuzzCorpus {

    const val RESOURCE = "/fuzz-corpus.jsonl"

    /** Where `fuzz` writes the corpus, relative to the repository root. */
    const val SOURCE_PATH = "tools/upstream-diff/src/main/resources/fuzz-corpus.jsonl"

    /** The corpus as built into the tool, or nothing before the first fuzz run. */
    fun bundled(): List<String> =
        FuzzCorpus::class.java.getResourceAsStream(RESOURCE)?.use { parse(it.readBytes().decodeToString()) }.orEmpty()

    fun read(file: File): List<String> = if (file.isFile) parse(file.readText()) else emptyList()

    fun parse(text: String): List<String> =
        text.lineSequence().filter { it.isNotBlank() }.map { Json.decodeFromString(String.serializer(), it) }.toList()

    fun format(inputs: List<String>): String =
        inputs.joinToString(separator = "") { Json.encodeToString(String.serializer(), it) + "\n" }
}
