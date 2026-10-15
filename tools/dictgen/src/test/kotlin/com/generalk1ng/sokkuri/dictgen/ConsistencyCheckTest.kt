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

package com.generalk1ng.sokkuri.dictgen

import com.generalk1ng.sokkuri.SokkuriConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the profile closure check: the clone's profile configs must be
 * exactly the ported [SokkuriConfig] profiles plus the registered unported
 * ones, and each way of breaking that is reported by name.
 */
class ConsistencyCheckTest {

    private val ported = SokkuriConfig.entries.map { it.stem }.toSet()
    private val unported = ConsistencyCheck.UNPORTED_UPSTREAM_STEMS

    @Test
    fun acceptsPortedPlusRegisteredUnportedProfiles() {
        ConsistencyCheck.verifyConfigStems(ported + unported)
    }

    @Test
    fun rejectsAnUpstreamProfileNeitherPortedNorRegistered() {
        val error = assertFailsWith<DictgenException> {
            ConsistencyCheck.verifyConfigStems(ported + unported + "s2xx")
        }
        assertTrue("unported upstream config: s2xx.json" in error.message.orEmpty(), error.message)
    }

    @Test
    fun rejectsAPortedProfileMissingUpstream() {
        val error = assertFailsWith<DictgenException> {
            ConsistencyCheck.verifyConfigStems(ported - "s2t" + unported)
        }
        assertTrue("missing upstream config: s2t.json" in error.message.orEmpty(), error.message)
    }

    @Test
    fun rejectsAStaleUnportedRegistration() {
        val error = assertFailsWith<DictgenException> {
            ConsistencyCheck.verifyConfigStems(ported + (unported - "s2seal"))
        }
        assertTrue("stale unported registration: s2seal" in error.message.orEmpty(), error.message)
    }

    @Test
    fun upstreamProfileStemsSkipsTheSchemaAndNonJsonFiles() {
        val openccDir = kotlin.io.path.createTempDirectory("sokkuri-consistency").toFile()
        try {
            val configDir = openccDir.resolve("data/config").apply { mkdirs() }
            val names = listOf(
                "s2t.json",
                "t2s.json",
                "opencc_config.schema.json",
                "BUILD.bazel",
                "ConfigDictValidationTest.cpp",
            )
            for (name in names) configDir.resolve(name).writeText("")
            assertEquals(setOf("s2t", "t2s"), upstreamProfileStems(openccDir))
        } finally {
            openccDir.deleteRecursively()
        }
    }
}
