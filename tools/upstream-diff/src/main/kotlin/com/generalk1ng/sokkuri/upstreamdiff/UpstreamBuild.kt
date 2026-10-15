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

/**
 * Builds the OpenCC reference clone and the harness that drives it, and
 * hands out the harness ([UpstreamHarness]) as the oracle.
 *
 * The clone is the behavioral spec (see the project documentation), so the
 * oracle is a build of exactly that revision rather than a released binary.
 * Building it is the caller's cost to pay once; the result is cached in
 * [buildDir] and reused while the clone stays at the commit it was built
 * from, or until [rebuild] is set.
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

    /** Records the clone commit the cached build was made from. */
    private val builtStamp: File get() = File(buildDir, "sokkuri-opencc-commit")

    /** Records the SHA-256 of the harness source the cached binary was compiled from. */
    private val harnessStamp: File get() = File(workDir, "opencc_harness.sha256")

    /** Builds whatever is missing or stale, then returns the harness over this build. */
    fun prepare(): UpstreamHarness {
        require(openccDir.isDirectory) {
            "OpenCC clone not found at ${openccDir.absolutePath}; " +
                "run ./gradlew fetchOpencc to check it out at the pinned commit, or pass --opencc-dir"
        }
        // A build is an oracle only for the commit it was built from: once the
        // clone moves (a pin bump), the cached one would answer for the old
        // commit. A build without a stamp predates stamping and counts as moved.
        val library = File(buildDir, "src/libopencc.a")
        val commit = cloneCommit()
        val moved = library.isFile && commit != null &&
            commit != builtStamp.takeIf { it.isFile }?.readText()?.trim()
        val rebuildLibrary = rebuild || moved || !library.isFile
        if (rebuildLibrary) {
            if (moved && !rebuild) {
                println("[diff] clone moved to $commit since the cached build; rebuilding")
            }
            cmakeConfigure()
            cmakeBuild()
        }
        // The harness links the library statically, so it follows every
        // rebuild; it is also rebuilt whenever its own source has changed,
        // since a stale binary would speak an older line protocol.
        val harnessSource = resourceBytes(HARNESS_SOURCE)
        val sourceDigest = sha256(harnessSource)
        val harnessStale = harnessStamp.takeIf { it.isFile }?.readText()?.trim() != sourceDigest
        if (rebuildLibrary || harnessStale || !harnessBinary.isFile) {
            compileHarness(harnessSource)
            harnessStamp.writeText("$sourceDigest\n")
        }
        commit?.let { builtStamp.writeText("$it\n") }
        return UpstreamHarness(harnessBinary, configDir, dataDir)
    }

    /**
     * The commit the clone has checked out, or null when it is not a git
     * repository of its own; git run in a plain folder would answer for
     * whatever work tree encloses it. Without a commit the build is reused
     * until [rebuild], as before stamping.
     */
    private fun cloneCommit(): String? {
        if (!File(openccDir, ".git").exists()) return null
        val process = ProcessBuilder("git", "-C", openccDir.absolutePath, "rev-parse", "HEAD")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        return output.takeIf { process.waitFor() == 0 }
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

    private fun compileHarness(harnessSource: ByteArray) {
        val source = File.createTempFile("opencc_harness-", ".cpp")
        try {
            source.writeBytes(harnessSource)
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

    private fun resourceBytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(name)) { "missing resource $name" }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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

    private companion object {
        /** Classpath location of the harness source compiled into [harnessBinary]. */
        const val HARNESS_SOURCE = "/opencc_harness.cpp"
    }
}
