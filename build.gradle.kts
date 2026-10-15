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

import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

plugins {
    // Applied in the module build scripts; declared here so each plugin is
    // loaded once into the buildscript classloader.
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinPluginSerialization) apply false
    alias(libs.plugins.vanniktechMavenPublish) apply false
}

/**
 * Base of the tasks that manage the OpenCC reference clone (docs/architecture.md
 * §10): the gitignored checkout of upstream at [CLONE] that tools:dictgen
 * compiles from and tools:upstream-diff builds its oracle from. It is pinned
 * to the commit on the `Commit:` line of [PIN], the note of the golden corpus
 * copied from that same commit; the pin is the only record of which upstream
 * commit the repository is aligned with.
 *
 * The tasks declare no outputs, so they always run: the clone's state lives
 * outside Gradle's view, and asking git about it is cheap.
 */
abstract class OpenccCloneTask : DefaultTask() {

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:Inject
    protected abstract val exec: ExecOperations

    protected fun cloneDir(): File = repositoryRoot.get().asFile.resolve(CLONE)

    /** The commit the clone is pinned to. */
    protected fun pinnedCommit(): String {
        val commit = repositoryRoot.get().asFile.resolve(PIN).readLines()
            .firstOrNull { it.startsWith("Commit:") }
            ?.removePrefix("Commit:")
            ?.trim()
        if (commit == null || !COMMIT.matches(commit)) {
            throw GradleException("$PIN has no 40-hex 'Commit:' line to pin OpenCC to")
        }
        return commit
    }

    /**
     * Whether the clone is a git repository of its own. Without this check,
     * git run inside a plain `OpenCC/` folder would answer for Sokkuri's own
     * work tree, which contains it.
     */
    protected fun hasOwnRepository(): Boolean = cloneDir().resolve(".git").exists()

    /** The commit the clone has checked out, or null before its first checkout. */
    protected fun head(): String? = gitOrNull("rev-parse", "--verify", "--quiet", "HEAD^{commit}")

    /** Whether tracked files differ from the checked-out commit; untracked files do not count. */
    protected fun hasModifiedFiles(): Boolean =
        git("status", "--porcelain", "--untracked-files=no").isNotEmpty()

    /** Runs git in the clone and returns its output; a failure fails the task with git's message. */
    protected fun git(vararg args: String): String {
        val error = ByteArrayOutputStream()
        return execGit(args, error) ?: throw GradleException(
            "git ${args.joinToString(" ")} failed in $CLONE: ${error.toString().trim()}",
        )
    }

    /** Runs git in the clone as a probe: null when it exits non-zero, its message discarded. */
    protected fun gitOrNull(vararg args: String): String? = execGit(args, ByteArrayOutputStream())

    private fun execGit(args: Array<out String>, error: ByteArrayOutputStream): String? {
        val output = ByteArrayOutputStream()
        val result = exec.exec {
            commandLine("git", "-C", cloneDir().absolutePath, *args)
            standardOutput = output
            errorOutput = error
            isIgnoreExitValue = true
        }
        return if (result.exitValue == 0) output.toString().trim() else null
    }

    companion object {
        const val URL = "https://github.com/BYVoid/OpenCC.git"
        const val CLONE = "OpenCC"
        const val PIN = "sokkuri-runtime/src/commonTest/resources/testcases/UPSTREAM"
        private val COMMIT = Regex("[0-9a-f]{40}")
    }
}

/**
 * Checks the clone out at the pinned commit, the way `git submodule update
 * --init` would: a missing clone is created by a shallow fetch of that one
 * commit, an existing clone is fetched into only when it lacks the commit,
 * and either is then detached at it. A clone with modified tracked files is
 * never moved.
 */
abstract class FetchOpencc : OpenccCloneTask() {

    @TaskAction
    fun fetch() {
        val pinned = pinnedCommit()
        val clone = cloneDir()
        if (!clone.exists()) {
            exec.exec { commandLine("git", "init", "--quiet", clone.absolutePath) }
            git("remote", "add", "origin", URL)
        } else if (!hasOwnRepository()) {
            throw GradleException("$CLONE exists but is not a git repository; move it aside and rerun")
        }

        val head = head()
        if (head == pinned) {
            logger.lifecycle("OpenCC clone already at the pinned commit $pinned")
            return
        }
        if (hasModifiedFiles()) {
            throw GradleException(
                "$CLONE has modified tracked files; refusing to move it from $head to $pinned " +
                    "(the clone is the spec and is never edited; see git -C $CLONE status)",
            )
        }
        if (gitOrNull("cat-file", "-e", "$pinned^{commit}") == null) {
            // By URL rather than by remote name, so a clone of a fork works too.
            // A new or shallow clone gets one commit; a full clone stays full.
            val shallow = head == null || git("rev-parse", "--is-shallow-repository") == "true"
            val depth = if (shallow) arrayOf("--depth", "1") else emptyArray()
            logger.lifecycle("Fetching OpenCC $pinned${if (shallow) " (shallow)" else ""}")
            git("fetch", "--quiet", *depth, URL, pinned)
        }
        git("checkout", "--quiet", "--detach", pinned)
        logger.lifecycle("OpenCC clone checked out at the pinned commit $pinned")
    }
}

/**
 * Fails unless the clone sits at the pinned commit with unmodified tracked
 * files. The tasks that read the clone depend on it, so a stale or edited
 * clone stops them up front instead of surfacing as drift or divergence that
 * is not Sokkuri's.
 */
abstract class CheckOpenccClone : OpenccCloneTask() {

    @TaskAction
    fun verify() {
        val pinned = pinnedCommit()
        if (!hasOwnRepository()) {
            throw GradleException("No OpenCC clone at $CLONE/; run ./gradlew fetchOpencc")
        }
        val head = head()
        if (head != pinned) {
            throw GradleException(
                "$CLONE is at ${head ?: "no commit"}, but $PIN pins $pinned; " +
                    "run ./gradlew fetchOpencc (to move the pin, edit its Commit line first)",
            )
        }
        if (hasModifiedFiles()) {
            throw GradleException(
                "$CLONE has modified tracked files; the clone is the spec and is never edited " +
                    "(see git -C $CLONE status)",
            )
        }
    }
}

tasks.register<FetchOpencc>("fetchOpencc") {
    group = "upstream"
    description = "Checks out the OpenCC reference clone at the commit pinned in testcases/UPSTREAM"
    repositoryRoot = layout.projectDirectory
}

tasks.register<CheckOpenccClone>("checkOpenccClone") {
    group = "upstream"
    description = "Fails unless the OpenCC reference clone is unmodified at the pinned commit"
    repositoryRoot = layout.projectDirectory
}
