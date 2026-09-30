@file:OptIn(SokkuriInternalApi::class)

package com.generalk1ng.sokkuri

import com.generalk1ng.sokkuri.config.ConfigParser
import com.generalk1ng.sokkuri.config.DictionaryProvider
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
    fun textDictionaryMultiCandidatesUseFirst() {
        val dictionary: Dictionary = TextDictionaryFormat.decode("干\t幹 干 乾\n".encodeToByteArray())
        assertEquals(listOf("幹", "干", "乾"), dictionary.matchExact("干")!!.candidates)
    }
}
