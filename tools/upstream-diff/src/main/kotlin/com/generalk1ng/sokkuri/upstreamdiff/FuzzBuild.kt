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
import java.nio.file.Files

/**
 * Builds the libFuzzer target (`opencc_fuzz.cpp`) over a coverage-instrumented
 * build of the clone: a second cmake build beside the reference one, compiled
 * by a clang that ships libFuzzer. Apple's clang does not; Homebrew's LLVM and
 * most Linux distributions' clang do.
 *
 * Cached like the reference build: the instrumented library is rebuilt when
 * the clone commit, the compiler or the instrumentation changes, and the
 * target also when its source changes.
 */
internal class FuzzBuild(
    private val openccDir: File,
    private val buildDir: File,
    private val rebuild: Boolean,
) {

    private val workDir: File get() = File(buildDir.parentFile, "${buildDir.name}-target")

    private val binary: File get() = File(workDir, "opencc_fuzz")

    private val libraryStamp: Stamp get() = Stamp(File(workDir, "library.stamp"))

    private val targetStamp: Stamp get() = Stamp(File(workDir, "target.stamp"))

    /** Builds whatever is missing or stale and returns the fuzzer executable. */
    fun prepare(): File {
        val compiler = FuzzCompiler.find()
        val library = File(buildDir, "src/libopencc.a")
        val libraryKey = "${cloneCommit(openccDir)} ${compiler.cxx} $INSTRUMENTATION"
        val rebuildLibrary = rebuild || !library.isFile || !libraryStamp.matches(libraryKey)
        if (rebuildLibrary) {
            println("[fuzz] building the instrumented clone with ${compiler.cxx}")
            // A clean configure: cmake refuses to switch compilers in an existing build.
            buildDir.deleteRecursively()
            configure(compiler)
            runCommand(
                listOf(
                    "cmake", "--build", buildDir.absolutePath, "--target", "libopencc",
                    "-j", Runtime.getRuntime().availableProcessors().toString(),
                ),
            )
            libraryStamp.record(libraryKey)
        }
        val source = resourceBytes(FUZZ_SOURCE)
        val targetKey = "${sha256(source)} $libraryKey"
        if (rebuildLibrary || !binary.isFile || !targetStamp.matches(targetKey)) {
            compileTarget(compiler, source)
            targetStamp.record(targetKey)
        }
        return binary
    }

    private fun configure(compiler: FuzzCompiler) {
        runCommand(
            listOf(
                "cmake",
                "-S", openccDir.absolutePath,
                "-B", buildDir.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DBUILD_SHARED_LIBS=OFF",
                // OpenCC is a C++-only project, so no C compiler is configured.
                "-DCMAKE_CXX_COMPILER=${compiler.cxx}",
                // Coverage instrumentation without libFuzzer's main: the
                // target links that in, the library only reports edges.
                "-DCMAKE_CXX_FLAGS=$INSTRUMENTATION",
            ),
        )
    }

    private fun compileTarget(compiler: FuzzCompiler, source: ByteArray) {
        workDir.mkdirs()
        val sourceFile = File(workDir, "opencc_fuzz.cpp").apply { writeBytes(source) }
        val command = mutableListOf(
            compiler.cxx,
            "-std=c++17",
            "-O1",
            "-g",
            "-fsanitize=fuzzer",
            "-I", File(openccDir, "src").absolutePath,
            "-I", File(buildDir, "src").absolutePath,
            sourceFile.absolutePath,
            File(buildDir, "src/libopencc.a").absolutePath,
        )
        val marisa = checkNotNull(marisaArchive(buildDir)) { "the instrumented build produced no libmarisa.a" }
        command += marisa.absolutePath
        command += listOf("-o", binary.absolutePath)
        runCommand(command)
    }

    private companion object {
        const val FUZZ_SOURCE = "/opencc_fuzz.cpp"
        const val INSTRUMENTATION = "-fsanitize=fuzzer-no-link"
    }
}

/** A clang++ whose driver links libFuzzer. */
internal data class FuzzCompiler(val cxx: String) {

    companion object {

        /**
         * The first candidate that actually compiles and links a fuzz target:
         * `FUZZ_CXX`, then Homebrew's LLVM (unversioned, then `llvm@N` from
         * the newest), then `clang++` on the path.
         */
        fun find(): FuzzCompiler {
            val candidates = buildList {
                System.getenv("FUZZ_CXX")?.takeIf { it.isNotBlank() }?.let(::add)
                for (prefix in listOf("/opt/homebrew/opt", "/usr/local/opt")) {
                    add("$prefix/llvm/bin/clang++")
                    File(prefix).listFiles { file -> file.name.startsWith("llvm@") }.orEmpty()
                        .sortedByDescending { it.name.removePrefix("llvm@").toIntOrNull() ?: 0 }
                        .forEach { add("${it.path}/bin/clang++") }
                }
                add("clang++")
            }.distinct()
            val cxx = candidates.firstOrNull(::linksLibFuzzer) ?: error(
                "no clang++ that links libFuzzer was found (tried ${candidates.joinToString()}); " +
                    "install LLVM (macOS: brew install llvm) or point FUZZ_CXX at one",
            )
            return FuzzCompiler(cxx)
        }

        private fun linksLibFuzzer(cxx: String): Boolean {
            if ('/' in cxx && !File(cxx).canExecute()) return false
            val dir = Files.createTempDirectory("fuzz-probe").toFile()
            try {
                val probe = File(dir, "probe.cpp")
                probe.writeText(
                    "extern \"C\" int LLVMFuzzerTestOneInput(const unsigned char*, unsigned long) { return 0; }\n",
                )
                return succeeds(listOf(cxx, "-fsanitize=fuzzer", probe.path, "-o", File(dir, "probe").path))
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}
