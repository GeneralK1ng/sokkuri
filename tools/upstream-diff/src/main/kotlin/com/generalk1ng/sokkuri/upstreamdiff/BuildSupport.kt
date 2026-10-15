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

import java.io.File
import java.security.MessageDigest

/*
 * Helpers shared by the builds of the OpenCC clone: the reference build
 * ([UpstreamBuild]) and the coverage-instrumented one the fuzzer links.
 */

/**
 * Records which input a cached build artifact was made from, so the artifact
 * is rebuilt when that input changes: a clone commit, a source digest, a
 * compiler path. A missing stamp never matches, so artifacts that predate
 * stamping are rebuilt once.
 */
internal class Stamp(private val file: File) {

    fun matches(value: String): Boolean = file.takeIf { it.isFile }?.readText()?.trim() == value

    fun record(value: String) {
        file.parentFile?.mkdirs()
        file.writeText("$value\n")
    }
}

/**
 * The commit [openccDir] has checked out, or null when it is not a git
 * repository of its own; git run in a plain folder would answer for whatever
 * work tree encloses it.
 */
internal fun cloneCommit(openccDir: File): String? {
    if (!File(openccDir, ".git").exists()) return null
    val process = ProcessBuilder("git", "-C", openccDir.absolutePath, "rev-parse", "HEAD")
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    return output.takeIf { process.waitFor() == 0 }
}

/** Runs [command] with its output shown, failing on a non-zero exit. */
internal fun runCommand(command: List<String>, environment: Map<String, String> = emptyMap()) {
    val exit = runProcess(command, environment)
    check(exit == 0) { "command failed with exit $exit: ${command.joinToString(" ")}" }
}

/** Runs [command] with its output shown and returns its exit code. */
internal fun runProcess(command: List<String>, environment: Map<String, String> = emptyMap()): Int {
    val builder = ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
    builder.environment().putAll(environment)
    return builder.start().waitFor()
}

/** Whether [command] runs and exits zero, its output discarded. */
internal fun succeeds(command: List<String>): Boolean = try {
    ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
        .waitFor() == 0
} catch (_: Exception) {
    false
}

internal fun resourceBytes(name: String): ByteArray =
    checkNotNull(Stamp::class.java.getResourceAsStream(name)) { "missing resource $name" }.use { it.readBytes() }

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** The marisa archive a cmake build of the clone bundles; its directory follows the vendored version. */
internal fun marisaArchive(buildDir: File): File? =
    File(buildDir, "deps").walkTopDown().firstOrNull { it.isFile && it.name == "libmarisa.a" }
