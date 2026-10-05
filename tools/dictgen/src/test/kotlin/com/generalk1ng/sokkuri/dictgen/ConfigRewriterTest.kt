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

import com.generalk1ng.sokkuri.SokkuriInternalApi
import com.generalk1ng.sokkuri.config.ConfigDocument
import com.generalk1ng.sokkuri.config.DictDocument
import com.generalk1ng.sokkuri.config.JsonSupport
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the packaged-config rewriter (m1-dictgen-sok §3.3): JSONC
 * tolerance, rewriting of `ocd` and `ocd2` references to `.sok` files under
 * the `dictionary/` resource directory, structural preservation (group,
 * match_policy, may_output_tofu, segmentation, inline), and strict,
 * deterministic, idempotent output.
 */
class ConfigRewriterTest {

    private val strict = Json { ignoreUnknownKeys = true }

    @OptIn(SokkuriInternalApi::class)
    @Test
    fun rewriteConvertsOcdAndOcd2ToPackagedSokReferences() {
        val rewritten = rewrite(
            """
            {
              "conversion_chain": [
                { "dict": { "type": "ocd", "file": "STPhrases.ocd" } },
                { "dict": { "type": "ocd2", "file": "TSCharacters.ocd2" } }
              ]
            }
            """,
        )
        val document = decode(rewritten)
        assertEquals(
            listOf<DictDocument>(
                DictDocument.Sok("dictionary/STPhrases.sok"),
                DictDocument.Sok("dictionary/TSCharacters.sok"),
            ),
            document.conversionChain.map { it.dict },
        )
    }

    @OptIn(SokkuriInternalApi::class)
    @Test
    fun rewritePreservesGroupStructureAndMatchPolicyRecursively() {
        val rewritten = rewrite(
            """
            {
              "conversion_chain": [
                {
                  "dict": {
                    "type": "group",
                    "match_policy": "all",
                    "dicts": [
                      { "type": "ocd", "file": "A.ocd" },
                      {
                        "type": "group",
                        "match_policy": "short_circuit",
                        "dicts": [ { "type": "ocd2", "file": "B.ocd2" } ]
                      }
                    ]
                  }
                }
              ]
            }
            """,
        )
        val document = decode(rewritten)
        val group = document.conversionChain.single().dict as DictDocument.Group
        assertEquals("all", group.matchPolicy)
        val inner = group.dicts[1] as DictDocument.Group
        assertEquals("short_circuit", inner.matchPolicy)
        assertEquals(
            DictDocument.Sok("dictionary/A.sok"),
            group.dicts[0],
        )
        assertEquals(
            DictDocument.Sok("dictionary/B.sok"),
            inner.dicts.single(),
        )
    }

    @OptIn(SokkuriInternalApi::class)
    @Test
    fun rewritePreservesTofuFlagsInlineEntriesAndSegmentation() {
        val rewritten = rewrite(
            """
            {
              "segmentation": {
                "type": "mmseg",
                "dict": { "type": "ocd2", "file": "STPhrases.ocd2" }
              },
              "conversion_chain": [
                { "dict": { "type": "ocd", "file": "TSCharacters.ocd", "may_output_tofu": true } },
                {
                  "dict": {
                    "type": "group",
                    "may_output_tofu": true,
                    "dicts": [
                      { "type": "inline", "entries": { "乾坤": "天地" } }
                    ]
                  }
                }
              ]
            }
            """,
        )
        val document = decode(rewritten)
        assertEquals(
            DictDocument.Sok("dictionary/STPhrases.sok"),
            document.segmentation?.dict,
        )
        val tofuDict = document.conversionChain[0].dict as DictDocument.Sok
        assertTrue(tofuDict.mayOutputTofu)
        val group = document.conversionChain[1].dict as DictDocument.Group
        assertTrue(group.mayOutputTofu)
        val inline = group.dicts.single() as DictDocument.Inline
        assertEquals(mapOf("乾坤" to "天地"), inline.entries)
    }

    @OptIn(SokkuriInternalApi::class)
    @Test
    fun rewriteToleratesJsoncCommentsAndCommentLikeTextInStrings() {
        val rewritten = rewrite(
            """
            // leading comment
            {
              "name": "s2t // not a comment", // trailing comment
              /* block comment
                 spanning lines */
              "conversion_chain": [
                { "dict": { "type": "ocd", "file": "STCharacters.ocd" } } // tail
              ]
            }
            """,
        )
        val document = decode(rewritten)
        assertEquals("s2t // not a comment", document.name)
        assertEquals(
            DictDocument.Sok("dictionary/STCharacters.sok"),
            document.conversionChain.single().dict,
        )
    }

    @OptIn(SokkuriInternalApi::class)
    @Test
    fun rewrittenOutputIsStrictDeterministicJsonWithSingleTrailingNewline() {
        val source = """
            {
              "name": "s2t",
              "segmentation": { "type": "mmseg", "dict": { "type": "ocd2", "file": "STPhrases.ocd2" } },
              "conversion_chain": [
                { "dict": { "type": "ocd", "file": "TSCharacters.ocd" } },
                {
                  "dict": {
                    "type": "group",
                    "dicts": [ { "type": "ocd2", "file": "TWVariants.ocd2" } ]
                  }
                }
              ]
            }
        """.trimIndent()
        val first = ConfigRewriter.rewrite(source)
        val second = ConfigRewriter.rewrite(source)
        assertEquals(first, second)
        assertTrue(first.endsWith("}\n"))
        assertTrue(!first.removeSuffix("\n").endsWith("\n"))
        // Strict decode without leniency must accept the output.
        assertEquals("s2t", strict.decodeFromString(ConfigDocument.serializer(), first).name)
        // Default-valued fields are spelled out so upstream flags survive
        // verbatim through a decode/encode round trip.
        assertTrue("\"match_policy\"" in first)
        assertTrue(first.lines().none { it.endsWith(" ") })
    }

    @Test
    fun rewriteIsIdempotentOnAlreadyPackagedConfigs() {
        val once = rewrite(
            """
            {
              "conversion_chain": [
                { "dict": { "type": "ocd", "file": "STPhrases.ocd" } },
                { "dict": { "type": "sok", "file": "dictionary/TSCharacters.sok", "may_output_tofu": true } },
                { "dict": { "type": "text", "file": "dictionary/pilot/TSCharacters.txt" } }
              ]
            }
            """,
        )
        val twice = ConfigRewriter.rewrite(once)
        assertEquals(once, twice)
    }

    @Test
    fun rewriteFailsOnUnparsableConfig() {
        assertFailsWith<SerializationException> {
            ConfigRewriter.rewrite("{ \"conversion_chain\": [ { \"dict\": 42 } ] }")
        }
        assertFailsWith<SerializationException> {
            ConfigRewriter.rewrite("{ not json at all ")
        }
    }

    private fun rewrite(jsonc: String): String = ConfigRewriter.rewrite(jsonc.trimIndent())

    @OptIn(SokkuriInternalApi::class)
    private fun decode(json: String): ConfigDocument =
        JsonSupport.opencc.decodeFromString(ConfigDocument.serializer(), json)
}
