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
 * The `configs` subcommand: loads every [ConfigCases] document with both
 * implementations under both tofu settings and reports where they part.
 * Returns the process exit code.
 */
internal fun runConfigs(options: Options): Int {
    println("[configs] preparing upstream at ${options.openccDir}")
    val upstream = UpstreamBuild(options.openccDir, options.buildDir, options.rebuild).prepare()

    // A fresh workspace each run: fixtures and configs are written from the
    // catalog, never edited in place.
    val workDir = File(options.buildDir.parentFile, "${options.buildDir.name}-configs")
    workDir.deleteRecursively()
    val fixtureDir = File(workDir, "fixtures").apply { mkdirs() }
    ConfigCases.fixtures.forEach { (name, content) -> File(fixtureDir, name).writeText(content) }
    ConfigCases.referenceFixtures.forEach { (name, source) ->
        File(upstream.dataDir, source).copyTo(File(fixtureDir, name))
    }
    val configDir = File(workDir, "configs").apply { mkdirs() }
    val configFiles = ConfigCases.all.map { case ->
        File(configDir, "${case.name}.json").apply { writeText(case.json) }
    }
    val probesFile = File(workDir, "probes.txt").apply { writeText(ConfigCases.probes.joinToString("") { "$it\n" }) }
    val fixtureCount = ConfigCases.fixtures.size + ConfigCases.referenceFixtures.size
    println(
        "[configs] ${ConfigCases.all.size} configs, $fixtureCount fixture dictionaries, " +
            "${ConfigCases.probes.size} probes, both tofu settings",
    )

    val sokkuri = SokkuriConfigLoader(fixtureDir)
    val divergent = LinkedHashMap<String, MutableList<String>>()
    for (includeTofu in listOf(false, true)) {
        val setting = if (includeTofu) "tofu included" else "tofu excluded"
        val expected = upstream.configs(fixtureDir, includeTofu, probesFile, configFiles)
        ConfigCases.all.forEachIndexed { index, case ->
            val actual = sokkuri.outcome(case.json, includeTofu, ConfigCases.probes)
            if (!expected[index].agreesWith(actual)) {
                divergent.getOrPut(case.name, ::mutableListOf) +=
                    "[$setting] upstream=${expected[index].render()}  sokkuri=${actual.render()}"
            }
        }
    }

    val registrations = ConfigCases.deliberateDivergences + ConfigCases.knownGaps
    val verdict = ConfigVerdict.of(divergent, registrations, ConfigCases.all.map { it.name })
    val what = ConfigCases.all.associate { it.name to it.what }
    for ((name, lines) in verdict.unexpected) {
        println("[configs] MISMATCH $name (${what[name]})")
        lines.forEach { println("[configs]   $it") }
    }
    for ((name, lines) in verdict.registered) {
        val label = if (name in ConfigCases.deliberateDivergences) "deliberate divergence" else "known gap"
        println("[configs] $label $name: ${registrations[name]}")
        lines.forEach { println("[configs]   $it") }
    }
    for (name in verdict.stale) {
        println("[configs] STALE registration $name: no such case, or both sides agree on it now")
    }
    println()
    val gaps = verdict.registered.keys.count { it in ConfigCases.knownGaps }
    println(
        "[configs] cases=${ConfigCases.all.size} mismatches=${verdict.unexpected.size} " +
            "deliberateDivergences=${verdict.registered.size - gaps} knownGaps=$gaps " +
            "staleRegistrations=${verdict.stale.size}",
    )
    if (verdict.aligned) {
        println("[configs] ALIGNED: every config is accepted, rejected and converted alike, registrations aside")
    } else {
        println("[configs] DIVERGED: see the cases above")
    }
    return if (verdict.aligned) 0 else 1
}

/**
 * The outcome of a config run: divergences nobody registered, registered
 * ones, and registrations that no longer match a divergence (an unknown
 * case name or a case both sides now agree on). Only the first and last
 * fail the run.
 */
internal data class ConfigVerdict(
    val unexpected: Map<String, List<String>>,
    val registered: Map<String, List<String>>,
    val stale: List<String>,
) {
    val aligned: Boolean get() = unexpected.isEmpty() && stale.isEmpty()

    companion object {
        fun of(
            divergent: Map<String, List<String>>,
            registrations: Map<String, String>,
            caseNames: List<String>,
        ): ConfigVerdict = ConfigVerdict(
            unexpected = divergent.filterKeys { it !in registrations },
            registered = divergent.filterKeys { it in registrations },
            stale = registrations.keys.filter { it !in caseNames || it !in divergent },
        )
    }
}

private fun ConfigOutcome.render(): String = when (this) {
    is ConfigOutcome.Accepted -> "accepted ${outputs.map { it.forDisplay() }}"
    is ConfigOutcome.Rejected -> "rejected at ${phase.name.lowercase()}: $category (${detail.forDisplay()})"
}
