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

/**
 * Builds the OpenCC reference clone and the per-input harness, then runs the
 * harness as an oracle.
 *
 * The clone is the behavioral spec (see the project documentation), so the
 * oracle is a build of exactly that revision rather than a released binary.
 * Building it is the caller's cost to pay once; the result is cached in
 * [buildDir] and reused until [rebuild] is set.
 */
internal class UpstreamBuild(
    private val openccDir: File,
    private val buildDir: File,
    private val rebuild: Boolean,
) {

    private val harnessBinary: File get() = File(workDir, "opencc_harness")

    private val workDir: File get() = File(buildDir.parentFile, "${buildDir.name}-harness")

    /** The config directory the harness loads stems from. */
    private val configDir: File get() = File(openccDir, "data/config")

    /** Where the build writes the compiled `.ocd2` dictionaries. */
    private val dataDir: File get() = File(buildDir, "data")

    fun prepare() {
        require(openccDir.isDirectory) {
            "OpenCC clone not found at ${openccDir.absolutePath}; " +
                "clone https://github.com/BYVoid/OpenCC there or pass --opencc-dir"
        }
        if (rebuild || !File(buildDir, "src/libopencc.a").isFile) {
            cmakeConfigure()
            cmakeBuild()
        }
        if (rebuild || !harnessBinary.isFile) {
            compileHarness()
        }
    }

    /**
     * Converts [inputs] with the reference implementation, one call per input.
     *
     * The corpus goes through a file rather than a pipe: a harness that fills
     * its stdout pipe while this side is still writing stdin would deadlock.
     */
    fun convert(stem: String, inputs: List<String>, includeTofu: Boolean): List<String> {
        val corpusFile = File.createTempFile("upstream-diff-", ".txt")
        val outputFile = File.createTempFile("upstream-diff-", ".out")
        try {
            corpusFile.writeText(inputs.joinToString("\n") + "\n")
            val command = mutableListOf(
                harnessBinary.absolutePath,
                stem,
                configDir.absolutePath,
                dataDir.absolutePath,
            )
            if (includeTofu) command += "1"

            val process = ProcessBuilder(command)
                .redirectInput(corpusFile)
                .redirectOutput(outputFile)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val exit = process.waitFor()
            check(exit == 0) { "harness exited $exit for profile '$stem'" }

            val lines = outputFile.readText().split("\n")
            // The harness terminates every output with a newline; drop the
            // artifact so the list lines up with the input list.
            return lines.dropLast(1)
        } finally {
            corpusFile.delete()
            outputFile.delete()
        }
    }

    private fun cmakeConfigure() {
        buildDir.mkdirs()
        run(
            "cmake",
            "-S", openccDir.absolutePath,
            "-B", buildDir.absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            // Static: the harness links the archive directly, which keeps the
            // oracle independent of any installed libopencc.
            "-DBUILD_SHARED_LIBS=OFF",
        )
    }

    private fun cmakeBuild() {
        val jobs = Runtime.getRuntime().availableProcessors().toString()
        run("cmake", "--build", buildDir.absolutePath, "-j", jobs)
    }

    private fun compileHarness() {
        val source = File.createTempFile("opencc_harness-", ".cpp")
        try {
            javaClass.getResourceAsStream("/opencc_harness.cpp")!!
                .use { input -> source.outputStream().use { input.copyTo(it) } }
            workDir.mkdirs()
            val command = mutableListOf(
                compiler(),
                "-std=c++17",
                "-O2",
                "-I", File(openccDir, "src").absolutePath,
                "-I", File(buildDir, "src").absolutePath,
                source.absolutePath,
                File(buildDir, "src/libopencc.a").absolutePath,
            )
            marisaArchive()?.let(command::add)
            command += listOf("-o", harnessBinary.absolutePath)
            run(*command.toTypedArray())
        } finally {
            source.delete()
        }
    }

    /** The bundled marisa archive, whose location follows the vendor version. */
    private fun marisaArchive(): String? =
        File(buildDir, "deps")
            .walkTopDown()
            .firstOrNull { it.isFile && it.name == "libmarisa.a" }
            ?.absolutePath

    private fun compiler(): String {
        val candidates = listOfNotNull(System.getenv("CXX"), "clang++", "g++")
        return candidates.firstOrNull { executable(it) }
            ?: error("no C++ compiler found; set CXX or install clang++/g++")
    }

    private fun executable(name: String): Boolean = try {
        ProcessBuilder(name, "--version")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun run(vararg command: String) {
        val process = ProcessBuilder(*command)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val exit = process.waitFor()
        check(exit == 0) { "command failed with exit $exit: ${command.joinToString(" ")}" }
    }
}
