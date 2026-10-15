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

@file:OptIn(SokkuriInternalApi::class)

package com.generalk1ng.sokkuri

import com.generalk1ng.sokkuri.config.ConfigParser
import com.generalk1ng.sokkuri.config.DictionaryProvider
import com.generalk1ng.sokkuri.config.JsonSupport
import com.generalk1ng.sokkuri.engine.Dictionary
import com.generalk1ng.sokkuri.resource.ResourceDictionaryProvider
import com.generalk1ng.sokkuri.resource.ResourceLoader
import com.generalk1ng.sokkuri.resource.TextDictionaryFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end config parsing (OpenCC `ConfigTest` essentials): inline
 * dictionaries, group policies, tofu filtering, normalization ordering,
 * JSONC comments, and file-backed text dictionaries through the resource
 * seam.
 */
class ConfigParserTest {

    private class FakeResourceLoader(
        private val files: Map<String, ByteArray>,
    ) : ResourceLoader {
        override fun load(path: String): ByteArray? = files[path]

        override fun missingResourceHint(): String = HINT

        companion object {
            /** Distinctive marker: proves the hint reaches the thrown failure (I9). */
            const val HINT = "in-memory fixture loader: the file is not in the fixture map"
        }
    }

    private fun providerOf(files: Map<String, String>): DictionaryProvider {
        val bytes = files.mapValues { (_, content) -> content.encodeToByteArray() }
        return ResourceDictionaryProvider(
            FakeResourceLoader(bytes),
            mapOf(TextDictionaryFormat.type to TextDictionaryFormat),
        )
    }

    private fun parse(
        json: String,
        files: Map<String, String> = emptyMap(),
        includeTofu: Boolean = false,
    ) = ConfigParser(providerOf(files), includeTofu).parse(json)

    @Test
    fun inlineDictionaryConverts() {
        val converter = parse(
            """
            {
              "conversion_chain": [ { "dict": { "type": "inline", "entries": { "发": "發", "动": "動", "干": "幹" } } } ]
            }
            """.trimIndent(),
        )
        assertEquals("發動幹部", converter.convert("发动干部"))
    }

    @Test
    fun groupPoliciesParse() {
        val files = mapOf(
            "phrases.txt" to "发火\tX\n",
            "chars.txt" to "发\t發\n",
        )
        val shortCircuit = parse(
            """
            {
              "conversion_chain": [ { "dict": {
                "type": "group", "match_policy": "short_circuit",
                "dicts": [ { "type": "text", "file": "phrases.txt" }, { "type": "text", "file": "chars.txt" } ]
              } } ]
            }
            """.trimIndent(),
            files,
        )
        assertEquals("X", shortCircuit.convert("发火"))
        assertEquals("發", shortCircuit.convert("发"))

        val union = parse(
            """
            {
              "conversion_chain": [ { "dict": {
                "type": "group", "match_policy": "union",
                "dicts": [ { "type": "text", "file": "phrases.txt" }, { "type": "text", "file": "chars.txt" } ]
              } } ]
            }
            """.trimIndent(),
            files,
        )
        // union: longest across children — "发火" only exists in phrases, so
        // both policies agree here; policy divergence is engine-tested.
        assertEquals("X", union.convert("发火"))
    }

    @Test
    fun defaultMatchPolicyIsShortCircuit() {
        val files = mapOf("a.txt" to "ab\tX\n", "b.txt" to "abc\tY\n")
        val converter = parse(
            """
            { "conversion_chain": [ { "dict": {
                "type": "group",
                "dicts": [ { "type": "text", "file": "a.txt" }, { "type": "text", "file": "b.txt" } ]
            } } ] }
            """.trimIndent(),
            files,
        )
        // Earlier child's shorter prefix match wins; trailing 'c' passes through.
        assertEquals("Xc", converter.convert("abc"))
    }

    @Test
    fun normalizationRunsBeforeConversion() {
        val converter = parse(
            """
            {
              "normalization": [ { "dict": { "type": "inline", "entries": { "豈": "豈" } } } ],
              "conversion_chain": [ { "dict": { "type": "inline", "entries": { "豈": "Q" } } } ]
            }
            """.trimIndent(),
        )
        // U+F900 (CJK compatibility form) normalizes to 豈, then converts to Q.
        assertEquals("Q", converter.convert("豈"))
    }

    @Test
    fun mmsegSegmentationSplitsBeforeChain() {
        val converter = parse(
            """
            {
              "segmentation": { "type": "mmseg", "dict": { "type": "inline", "entries": { "燕燕于飞": "燕燕于飞" } } },
              "conversion_chain": [ { "dict": { "type": "inline", "entries": { "燕燕于飞": "飛", "燕": "燕" } } } ]
            }
            """.trimIndent(),
        )
        assertEquals("飛", converter.convert("燕燕于飞"))

        val inspection = converter.inspect("燕燕于飞")
        assertEquals(listOf("燕燕于飞"), inspection.segments)
        assertEquals(1, inspection.stages.size)
        assertEquals(listOf("飛"), inspection.stages[0].segments)
        assertEquals("飛", inspection.output)
    }

    @Test
    fun tofuDictionaryExcludedByDefaultAndIncludedOnRequest() {
        // Two chain stages: base, then a tofu-flagged extension.
        val files = mapOf(
            "base.txt" to "干\t幹\n",
            "ext.txt" to "幹\t乾\n",
        )
        val json = """
            { "conversion_chain": [
                { "dict": { "type": "text", "file": "base.txt" } },
                { "dict": { "type": "text", "file": "ext.txt", "may_output_tofu": true } }
            ] }
        """.trimIndent()

        val withoutTofu = parse(json, files)
        assertEquals("幹", withoutTofu.convert("干")) // ext stage dropped

        val withTofu = parse(json, files, includeTofu = true)
        assertEquals("乾", withTofu.convert("干")) // 干→幹→乾, both stages
    }

    @Test
    fun allTofuGroupStagesDroppedYieldsIdentity() {
        val files = mapOf("ext.txt" to "干\t幹\n")
        val converter = parse(
            """
            { "conversion_chain": [ { "dict": { "type": "text", "file": "ext.txt", "may_output_tofu": true } } ] }
            """.trimIndent(),
            files,
        )
        assertEquals("干部", converter.convert("干部"))
    }

    @Test
    fun jsoncCommentsAndTrailingCommasAreAccepted() {
        val converter = parse(
            """
            {
              // line comment
              "conversion_chain": [
                { "dict": { "type": "inline", "entries": { "a": "A" } } }, // trailing
              ],
            }
            """.trimIndent(),
        )
        assertEquals("A", converter.convert("a"))
    }

    @Test
    fun nameThatIsNotAStringIsIgnoredLikeUpstream() {
        // Config.cpp reads `name` only when it is a string; anything else is
        // ignored rather than failing the config.
        for (name in listOf("5", "null", "true", "[\"a\"]", "{\"x\": 1}", "\"a string\"")) {
            val converter = parse(
                """{"name": $name, "conversion_chain": [{"dict": {"type": "inline", "entries": {"a": "A"}}}]}""",
            )
            assertEquals("A", converter.convert("a"), "name $name")
        }
    }

    @Test
    fun chainMembersThatAreNotObjectsAreSkippedLikeUpstream() {
        // ParseConversionChain converts object members and silently skips the rest.
        val stage = """{"dict": {"type": "inline", "entries": {"a": "A"}}}"""
        val converter = parse("""{"conversion_chain": [1, "x", null, [], $stage, true]}""")
        assertEquals("A", converter.convert("a"))
        val normalization = """[0, {"dict": {"type": "inline", "entries": {"b": "a"}}}]"""
        val normalized = parse("""{"normalization": $normalization, "conversion_chain": [$stage]}""")
        assertEquals("A", normalized.convert("b"))
    }

    @Test
    fun aConversionListThatIsNotAnArrayIsInvalid() {
        for (json in listOf("""{"conversion_chain": {}}""", """{"normalization": 1, "conversion_chain": []}""")) {
            assertFailsWith<SokkuriException.InvalidConfig>(json) { parse(json) }
        }
    }

    @Test
    fun aRepeatedMemberResolvesToItsFirstOccurrenceLikeUpstream() {
        // rapidjson's FindMember returns the first of repeated members; the
        // decoder alone would keep the last.
        val a = """{"dict": {"type": "inline", "entries": {"a": "A"}}}"""
        assertEquals("A", parse("""{"conversion_chain": [$a], "conversion_chain": []}""").convert("a"))
        // Keys are compared decoded: an escaped spelling is the same member.
        assertEquals("A", parse("""{"conversion_chain": [$a], "conversion\u005fchain": []}""").convert("a"))
        // At any depth: the first type, the first entries, the first policy.
        val nested = """{"conversion_chain": [{"dict": {"type": "group", "match_policy": "union",
            "match_policy": "longest", "dicts": [{"type": "inline", "type": "text", "entries": {"a": "A"},
            "entries": {"a": "B"}}]}}]}"""
        assertEquals("A", parse(nested).convert("a"))
    }

    @Test
    fun preparationDropsLaterRepeatsAndLeavesUnreadableTextAlone() {
        assertEquals("""{"a": 1, "b": 3}""", JsonSupport.prepare("""{"a": 1, "a": 2, "b": 3}"""))
        assertEquals("""{"a": 1,}""", JsonSupport.prepare("""{"a": 1, "a": {"x": 1, "x": 2},}"""))
        assertEquals("""[{"k": [1, {"k": 2}]}]""", JsonSupport.prepare("""[{"k": [1, {"k": 2, "k": 3}], "k": 4}]"""))
        assertEquals("{\n  \"a\": 1\n}", JsonSupport.prepare("{\n  \"a\": 1, // c\n  \"a\": 2\n}"))
        for (unreadable in listOf("""{"a": 1, "a": """, """{"a" 1, "a": 2}""", """{'a': 1, 'a': 2}""")) {
            assertEquals(unreadable, JsonSupport.prepare(unreadable), "unreadable text passes through: $unreadable")
        }
    }

    @Test
    fun anInlineKeyListedTwiceIsInvalidLikeUpstream() {
        val repeated = """{"type": "inline", "entries": {"a": "A", "\u0061": "B"}}"""
        val placements = listOf(
            """{"conversion_chain": [{"dict": $repeated}]}""",
            """{"normalization": [{"dict": $repeated}], "conversion_chain": []}""",
            """{"segmentation": {"type": "mmseg", "dict": $repeated}, "conversion_chain": []}""",
            """{"conversion_chain": [{"dict": {"type": "group", "dicts": [{"type": "inline", "entries": {"b": "B"}},
                $repeated]}}]}""",
            // Indices count object members, as the decoded chain does.
            """{"conversion_chain": [{"dict": {"type": "inline", "entries": {"b": "B"}}}, 7, {"dict": $repeated}]}""",
        )
        for (json in placements) {
            assertFailsWith<SokkuriException.InvalidConfig>(json) { parse(json) }
        }
    }

    @Test
    fun aRepeatedInlineKeyIsOnlyAnErrorWhereUpstreamLoadsTheDictionary() {
        // A tofu-risk group is dropped before its members are parsed, so its
        // inline members are never checked, upstream or here.
        val json = """{"conversion_chain": [{"dict": {"type": "group", "may_output_tofu": true,
            "dicts": [{"type": "inline", "entries": {"a": "A", "a": "B"}}]}}]}"""
        assertEquals("a", parse(json, includeTofu = false).convert("a"))
        assertFailsWith<SokkuriException.InvalidConfig> { parse(json, includeTofu = true) }
        // A repeat outside an inline dictionary's entries is just a repeated member.
        val repeatedMember = """{"conversion_chain": [{"dict": {"type": "inline", "entries": {"a": "A"},
            "entries": {"a": "B"}}}]}"""
        assertEquals("A", parse(repeatedMember).convert("a"))
    }

    @Test
    fun unknownSegmentationTypeIsUnsupported() {
        assertFailsWith<SokkuriException.Unsupported> {
            parse(
                """
                {
                  "segmentation": { "type": "jieba" },
                  "conversion_chain": [ { "dict": { "type": "inline", "entries": { "a": "A" } } } ]
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun unknownMatchPolicyIsInvalid() {
        assertFailsWith<SokkuriException.InvalidConfig> {
            parse(
                """
                { "conversion_chain": [ { "dict": {
                    "type": "group", "match_policy": "bogus", "dicts": []
                } } ] }
                """.trimIndent(),
            )
        }
    }

    /**
     * `type` is a serializer-level discriminator, so the config layer never
     * sees its value; the wording comes from the module-level default
     * deserializer in `JsonSupport` (architecture.md §9, D11).
     */
    @Test
    fun unknownDictionaryTypeIsInvalid() {
        val failure = assertFailsWith<SokkuriException.InvalidConfig> {
            parse(
                """
                { "conversion_chain": [ { "dict": { "type": "text2", "file": "a.txt" } } ] }
                """.trimIndent(),
            )
        }
        assertEquals("Invalid configuration: unknown dictionary type 'text2'", failure.message)
    }

    /** The same seam catches a missing discriminator, which fails the same lookup. */
    @Test
    fun dictionaryEntryWithoutTypeIsInvalid() {
        val failure = assertFailsWith<SokkuriException.InvalidConfig> {
            parse(
                """
                { "conversion_chain": [ { "dict": { "file": "a.txt" } } ] }
                """.trimIndent(),
            )
        }
        assertEquals("Invalid configuration: dictionary entry has no 'type' field", failure.message)
    }

    /** The rejection is not top-level-only: group children decode through the same module. */
    @Test
    fun unknownDictionaryTypeInsideAGroupIsInvalid() {
        val failure = assertFailsWith<SokkuriException.InvalidConfig> {
            parse(
                """
                { "conversion_chain": [ { "dict": {
                    "type": "group", "match_policy": "union",
                    "dicts": [ { "type": "bogus", "file": "a.txt" } ]
                } } ] }
                """.trimIndent(),
            )
        }
        assertEquals("Invalid configuration: unknown dictionary type 'bogus'", failure.message)
    }

    @Test
    fun missingDictionaryFileThrowsFileNotFound() {
        val failure = assertFailsWith<SokkuriException.FileNotFound> {
            parse(
                """
                { "conversion_chain": [ { "dict": { "type": "text", "file": "absent.txt" } } ] }
                """.trimIndent(),
            )
        }
        assertTrue(failure.message!!.contains("absent.txt"))
        // The loader's diagnosis travels with the miss (architecture.md §6, I9).
        assertTrue(
            failure.message!!.contains(FakeResourceLoader.HINT),
            "expected the loader's missing-resource hint in: ${failure.message}",
        )
    }

    @Test
    fun duplicateKeysInTextDictionaryAreInvalid() {
        assertFailsWith<SokkuriException.InvalidFormat> {
            parse(
                """
                { "conversion_chain": [ { "dict": { "type": "text", "file": "dup.txt" } } ] }
                """.trimIndent(),
                files = mapOf("dup.txt" to "a\tA\na\tA2\n"),
            )
        }
    }

    @Test
    fun bomAndCommentsInTextDictionaryAreIgnored() {
        val converter = parse(
            """
            { "conversion_chain": [ { "dict": { "type": "text", "file": "d.txt" } } ] }
            """.trimIndent(),
            files = mapOf("d.txt" to "﻿# comment\n干\t幹\n\n"),
        )
        assertEquals("幹", converter.convert("干"))
    }

    @Test
    fun emptyCandidatesFromSpacesAreKeptLikeUpstream() {
        // ParseKeyValues splits on every space: a space after the tab makes
        // the empty string the default, and nothing after the tab maps the
        // key to the empty string; a space after a candidate only appends an
        // empty alternative.
        val config = """{"conversion_chain": [{"dict": {"type": "text", "file": "d.txt"}}]}"""
        fun convert(dictionary: String) = parse(config, files = mapOf("d.txt" to dictionary)).convert("干燥")
        assertEquals("燥", convert("干\t 乾\n"))
        assertEquals("燥", convert("干\t\n"))
        assertEquals("乾燥", convert("干\t乾  幹\n"))
        assertEquals("乾燥", convert("干\t乾 \n"))
    }

    @Test
    fun textDictionaryMultiCandidatesUseFirst() {
        val dictionary: Dictionary = TextDictionaryFormat.decode("干\t幹 干 乾\n".encodeToByteArray())
        assertEquals(listOf("幹", "干", "乾"), dictionary.matchExact("干")!!.candidates)
    }

    @Test
    fun normalizationStagesAreReportedBeforeTheMainChain() {
        val converter = parse(
            """
            {
              "normalization": [ { "dict": { "type": "inline", "entries": { "a": "A" } } } ],
              "segmentation": { "type": "mmseg", "dict": { "type": "inline", "entries": { "A": "A" } } },
              "conversion_chain": [ { "dict": { "type": "inline", "entries": { "A": "X" } } } ]
            }
            """.trimIndent(),
        )
        assertEquals("X", converter.convert("a"))

        val inspection = converter.inspect("a")
        assertEquals(1, inspection.normalizationStages.size)
        assertEquals(1, inspection.normalizationStages[0].index)
        assertEquals(listOf("A"), inspection.normalizationStages[0].segments)
        assertEquals(listOf("A"), inspection.segments)
        assertEquals(1, inspection.stages.size)
        assertEquals(1, inspection.stages[0].index)
        assertEquals(listOf("X"), inspection.stages[0].segments)
        assertEquals("X", inspection.output)
    }

    @Test
    fun inlineDictionaryRejectsMayOutputTofu() {
        val failure = assertFailsWith<SokkuriException.InvalidFormat> {
            parse(
                """
                { "conversion_chain": [ { "dict": {
                    "type": "inline", "may_output_tofu": true,
                    "entries": { "a": "A" }
                } } ] }
                """.trimIndent(),
            )
        }
        assertTrue(failure.message!!.contains("may_output_tofu"))
    }

    @Test
    fun inlineDictionaryRejectsMayOutputTofuOnPresenceAlone() {
        // Upstream `LoadInlineDict` tests `doc.HasMember("may_output_tofu")`
        // before it reads the value, so an explicit `false` is rejected just
        // like `true`. Verified against a build of the reference clone: the
        // `false` form throws opencc::InvalidFormat there.
        val failure = assertFailsWith<SokkuriException.InvalidFormat> {
            parse(
                """
                { "conversion_chain": [ { "dict": {
                    "type": "inline", "may_output_tofu": false,
                    "entries": { "a": "A" }
                } } ] }
                """.trimIndent(),
            )
        }
        assertTrue(failure.message!!.contains("may_output_tofu"))
    }

    @Test
    fun inlineDictionaryWithoutTheKeyIsAccepted() {
        val converter = parse(
            """
            { "conversion_chain": [ { "dict": {
                "type": "inline", "entries": { "a": "A" }
            } } ] }
            """.trimIndent(),
        )
        assertEquals("A", converter.convert("a"))
    }
}
