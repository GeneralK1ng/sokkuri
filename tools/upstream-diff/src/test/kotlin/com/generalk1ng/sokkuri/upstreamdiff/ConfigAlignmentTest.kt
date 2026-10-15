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

import com.generalk1ng.sokkuri.SokkuriException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The config comparison's moving parts: the harness answer protocol, the
 * outcome categories on both sides, what counts as agreement, the
 * registry verdict, Sokkuri's loader, and the case catalog's consistency.
 */
class ConfigAlignmentTest {

    private fun answer(line: String): ConfigOutcome =
        Json.decodeFromString(HarnessConfigAnswer.serializer(), line).toOutcome()

    @Test
    fun decodesBothAnswerShapes() {
        assertEquals(
            ConfigOutcome.Accepted(listOf("乾", "")),
            answer("""{"status":"accepted","outputs":["乾",""]}"""),
        )
        val rejected = answer(
            """{"status":"rejected","phase":"convert","exception":"InvalidTextDictionary","message":"line 1"}""",
        )
        assertEquals(ConfigOutcome.Rejected(Phase.CONVERT, Category.INVALID, "InvalidTextDictionary: line 1"), rejected)
    }

    @Test
    fun malformedAnswersAreProtocolErrors() {
        assertFailsWith<IllegalStateException> { answer("""{"status":"maybe"}""") }
        assertFailsWith<IllegalStateException> { answer("""{"status":"accepted"}""") }
        assertFailsWith<IllegalStateException> {
            answer("""{"status":"rejected","phase":"later","exception":"InvalidFormat","message":""}""")
        }
    }

    @Test
    fun upstreamExceptionsMapOntoCategories() {
        assertEquals(Category.MISSING_RESOURCE, HarnessConfigAnswer.upstreamCategory("FileNotFound"))
        assertEquals(Category.INVALID, HarnessConfigAnswer.upstreamCategory("InvalidFormat"))
        assertEquals(Category.INVALID, HarnessConfigAnswer.upstreamCategory("InvalidTextDictionary"))
        assertEquals(Category.OTHER, HarnessConfigAnswer.upstreamCategory("ShouldNotBeHere"))
        assertEquals(Category.OTHER, HarnessConfigAnswer.upstreamCategory("std::exception"))
    }

    @Test
    fun sokkuriExceptionsMapOntoCategories() {
        assertEquals(Category.MISSING_RESOURCE, SokkuriException.FileNotFound("a").category())
        assertEquals(Category.INVALID, SokkuriException.InvalidConfig("a").category())
        assertEquals(Category.INVALID, SokkuriException.InvalidFormat("a").category())
        assertEquals(Category.UNSUPPORTED, SokkuriException.Unsupported("a").category())
    }

    @Test
    fun agreementComparesOutputsOrPhaseAndCategoryButNeverMessages() {
        val accepted = ConfigOutcome.Accepted(listOf("x"))
        val rejected = ConfigOutcome.Rejected(Phase.LOAD, Category.INVALID, "upstream wording")
        assertTrue(accepted.agreesWith(ConfigOutcome.Accepted(listOf("x"))))
        assertFalse(accepted.agreesWith(ConfigOutcome.Accepted(listOf("y"))))
        assertTrue(rejected.agreesWith(rejected.copy(detail = "Sokkuri wording")))
        assertFalse(rejected.agreesWith(rejected.copy(phase = Phase.CONVERT)))
        assertFalse(rejected.agreesWith(rejected.copy(category = Category.MISSING_RESOURCE)))
        assertFalse(accepted.agreesWith(rejected))
        assertFalse(rejected.agreesWith(accepted))
    }

    @Test
    fun verdictSeparatesUnexpectedRegisteredAndStale() {
        val divergent = mapOf("new" to listOf("a"), "known" to listOf("b"))
        val registrations = mapOf("known" to "why", "fixed" to "why", "typo" to "why")
        val verdict = ConfigVerdict.of(divergent, registrations, caseNames = listOf("new", "known", "fixed"))
        assertEquals(setOf("new"), verdict.unexpected.keys)
        assertEquals(setOf("known"), verdict.registered.keys)
        assertEquals(listOf("fixed", "typo"), verdict.stale)
        assertFalse(verdict.aligned)
        assertTrue(ConfigVerdict.of(mapOf("known" to listOf("b")), mapOf("known" to "why"), listOf("known")).aligned)
    }

    @Test
    fun sokkuriLoaderReportsEachOutcome() {
        val dir = Files.createTempDirectory("config-alignment").toFile()
        try {
            dir.resolve("chars.txt").writeText("干\t乾\n")
            dir.resolve("binary.ocd2").writeBytes(byteArrayOf(1, 2, 3))
            val loader = SokkuriConfigLoader(dir)
            fun load(json: String) = loader.outcome(json, includeTofu = false, probes = listOf("干燥"))

            assertEquals(
                ConfigOutcome.Accepted(listOf("乾燥")),
                load("""{"conversion_chain": [{"dict": {"type": "text", "file": "chars.txt"}}]}"""),
            )
            val missing = assertIs<ConfigOutcome.Rejected>(
                load("""{"conversion_chain": [{"dict": {"type": "text", "file": "absent.txt"}}]}"""),
            )
            assertEquals(Phase.LOAD to Category.MISSING_RESOURCE, missing.phase to missing.category)
            val invalid = assertIs<ConfigOutcome.Rejected>(load("""{"conversion_chain": """))
            assertEquals(Category.INVALID, invalid.category)
            val unsupported = assertIs<ConfigOutcome.Rejected>(
                load("""{"conversion_chain": [{"dict": {"type": "ocd2", "file": "binary.ocd2"}}]}"""),
            )
            assertEquals(Category.UNSUPPORTED, unsupported.category)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun catalogIsConsistent() {
        val names = ConfigCases.all.map { it.name }
        assertEquals(names.size, names.toSet().size, "case names must be unique")
        val registered = ConfigCases.deliberateDivergences.keys + ConfigCases.knownGaps.keys
        assertTrue(names.containsAll(registered), "registrations must name existing cases")
        assertTrue(
            ConfigCases.deliberateDivergences.keys.intersect(ConfigCases.knownGaps.keys).isEmpty(),
            "a case is either a deliberate divergence or a known gap",
        )
        assertTrue(ConfigCases.probes.none { '\n' in it || '\r' in it }, "probes travel one per line")
        assertTrue(
            ConfigCases.fixtures.keys.intersect(ConfigCases.referenceFixtures.keys).isEmpty(),
            "fixture names must not collide",
        )
    }
}
