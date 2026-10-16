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

import java.io.File
import javax.inject.Inject

// The independent .sok reader is a cargo workspace (docs/design/sok-rs.md
// §10.1). This project applies no plugin, so it has no build, check or test
// lifecycle task and `./gradlew build` never reaches it: it only registers
// tasks that run cargo, for the one entry point `./gradlew sokConformance`.

/**
 * Runs one cargo command in this workspace.
 *
 * cargo is resolved when the task runs, never when the build is configured:
 * the CARGO environment variable, then ~/.cargo/bin/cargo, then PATH. The
 * middle one matters: rustup writes its PATH line only into ~/.profile, which
 * zsh never reads, so a Gradle daemon started from zsh does not see cargo on
 * PATH even where it is installed.
 *
 * State is not tracked: cargo keeps its own incremental state, and these
 * tasks are checks that must run every time they are asked for.
 */
abstract class CargoTask : DefaultTask() {

    /** Arguments after `cargo`. */
    @get:Input
    abstract val arguments: ListProperty<String>

    /** Extra environment for the cargo process. */
    @get:Input
    abstract val extraEnvironment: MapProperty<String, String>

    /** When set, cargo's standard output is written to this file instead of the console. */
    @get:Optional
    @get:OutputFile
    abstract val standardOutputFile: RegularFileProperty

    @get:Internal
    abstract val workspace: DirectoryProperty

    @get:Inject
    protected abstract val exec: ExecOperations

    init {
        group = "sok-rs"
        doNotTrackState("cargo tracks its own inputs; these checks run whenever they are asked for")
    }

    @TaskAction
    fun run() {
        val cargo = resolveCargo()
        val output = standardOutputFile.orNull?.asFile
        output?.parentFile?.mkdirs()
        val result = (output?.outputStream() ?: System.out).let { stream ->
            try {
                exec.exec {
                    executable = cargo
                    args(arguments.get())
                    environment(extraEnvironment.get())
                    workingDir = workspace.get().asFile
                    standardOutput = stream
                    isIgnoreExitValue = true
                }
            } finally {
                if (output != null) stream.close()
            }
        }
        if (result.exitValue != 0) {
            throw GradleException("cargo ${arguments.get().joinToString(" ")} exited with ${result.exitValue}")
        }
    }

    private fun resolveCargo(): String {
        System.getenv("CARGO")?.takeIf { it.isNotBlank() }?.let { configured ->
            if (!File(configured).canExecute()) {
                throw GradleException("CARGO is set to $configured, which is not an executable file")
            }
            return configured
        }
        val rustupCargo = File(System.getProperty("user.home"), ".cargo/bin/cargo")
        if (rustupCargo.canExecute()) return rustupCargo.absolutePath
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, "cargo") }
            .firstOrNull { it.canExecute() }
        return onPath?.absolutePath ?: throw GradleException(
            "cargo not found: set CARGO, install it at ~/.cargo/bin with rustup (https://rustup.rs), " +
                "or put it on PATH. Rust is needed only for the tools/sok-rs tasks, never for the main build.",
        )
    }
}

val cargoTask: CargoTask.() -> Unit = {
    workspace = layout.projectDirectory
}

val cargoFmtCheck by tasks.registering(CargoTask::class) {
    cargoTask()
    description = "Checks that the Rust sources are formatted (rustfmt.toml)."
    arguments = listOf("fmt", "--all", "--check")
}

val cargoClippy by tasks.registering(CargoTask::class) {
    cargoTask()
    description = "Runs clippy on every target with warnings as errors."
    arguments = listOf("clippy", "--workspace", "--all-targets", "--locked", "--", "-D", "warnings")
}

val cargoDoc by tasks.registering(CargoTask::class) {
    cargoTask()
    description = "Builds the rustdoc, failing on any warning."
    arguments = listOf("doc", "--workspace", "--no-deps", "--locked")
    extraEnvironment = mapOf("RUSTDOCFLAGS" to "-D warnings")
}

val cargoLint by tasks.registering {
    group = "sok-rs"
    description = "Formatting, clippy and rustdoc checks of tools/sok-rs."
    dependsOn(cargoFmtCheck, cargoClippy, cargoDoc)
}

val cargoTest by tasks.registering(CargoTask::class) {
    cargoTask()
    description = "Runs the Rust tests of tools/sok-rs."
    arguments = listOf("test", "--workspace", "--locked")
    mustRunAfter(cargoLint)
}

/** The dictionaries the runtime ships, which the Rust reader must read as the Kotlin decoder does. */
val packagedDictionaries: String =
    layout.projectDirectory.dir("../../sokkuri-runtime/src/commonMain/resources/dictionary").asFile.normalize().path

val sokcheck = listOf("run", "--quiet", "--locked", "--package", "sokcheck", "--")

val verifyPackaged by tasks.registering(CargoTask::class) {
    cargoTask()
    description = "Checks every packaged dictionary against the acceptance rules."
    arguments = sokcheck + listOf("verify", packagedDictionaries)
    mustRunAfter(cargoTest)
}

val expectPackaged by tasks.registering(CargoTask::class) {
    cargoTask()
    description = "Writes the Rust reader's expectations for the packaged dictionaries to build/sok."
    arguments = sokcheck + listOf("expect", "--packaged", packagedDictionaries)
    standardOutputFile = layout.buildDirectory.file("sok/packaged-expect.txt")
    mustRunAfter(cargoTest)
}

tasks.register("sokConformance") {
    group = "sok-rs"
    description = "Every check of the independent .sok reader (docs/architecture.md §10.5)."
    dependsOn(cargoLint, cargoTest, verifyPackaged, ":sokkuri-runtime:sokConformanceTest")
}
