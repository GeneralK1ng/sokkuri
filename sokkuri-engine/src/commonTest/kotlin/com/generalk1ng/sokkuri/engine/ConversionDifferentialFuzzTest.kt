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

@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)
package com.generalk1ng.sokkuri.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port of OpenCC's `ConversionTest.DifferentialFuzzAgainstReferenceLoop`.
 *
 * The bulk-skip optimization must be invisible: [Conversion] has to produce
 * exactly what a loop that advances one character (or one complete IDS) at a
 * time — and never skips — produces. The fuzz corpus mixes dictionary keys,
 * unrelated CJK, ASCII, IDS operators, non-BMP text and unpaired surrogates
 * (this engine's analogue of upstream's truncated UTF-8), and runs against
 * both a single dictionary and a group, mirroring upstream's two targets.
 *
 * This is the test that pins the skip predicate as a whole. It fails on a
 * skip loop that stops only at dictionary key-starts: an IDS reached inside
 * an unmatched run then loses its grouping and its operands get converted.
 */
class ConversionDifferentialFuzzTest {

    /**
     * Reference implementation of the conversion loop: one character or one
     * complete IDS per unmatched position, no bulk skip. This is the
     * pre-skip-scan behavior that [Conversion] must stay equivalent to.
     */
    private fun referenceConvert(dict: Dictionary, input: CharArray): String {
        val out = StringBuilder()
        var i = 0
        while (i < input.size) {
            val match = dict.matchPrefix(input, i, input.size)
            if (match != null) {
                out.append(match.value)
                i += match.length
            } else {
                var width = Utf.ideographicDescriptionSequenceLength(input, i, input.size)
                if (width == 0) width = Utf.charCount(Utf.codePointAt(input, i, input.size))
                out.append(input.concatToString(i, i + width))
                i += width
            }
        }
        return out.toString()
    }

    private val fragments = listOf(
        "太后", "头发", "干燥", "鼠标", "干", "后", "发", "里",
        "天地", "、。", "——",
        "⿰", "⿳", "⿾",
        "🎉", "ascii text ", "12345",
        // Unpaired surrogates: the UTF-16 analogue of truncated UTF-8.
        "\uD800", "\uDC00",
    )

    private fun fuzzInput(rng: Random): String {
        val sb = StringBuilder()
        val pieces = 1 + rng.nextInt(40)
        repeat(pieces) {
            val choice = rng.nextInt(10)
            when {
                choice < 7 -> sb.append(fragments[rng.nextInt(fragments.size)])
                choice < 9 -> sb.append(Char(rng.nextInt(0x10000)))
                sb.isNotEmpty() -> sb.deleteAt(sb.length - 1)
            }
        }
        return sb.toString()
    }

    private fun assertEquivalentWith(dict: Dictionary) {
        val conversion = Conversion(dict)
        // One fixed seed per target keeps failures reproducible; the sequence
        // is long enough that every fragment combination is well covered.
        val rng = Random(20260724)
        repeat(600) { iteration ->
            val input = fuzzInput(rng)
            val expected = referenceConvert(dict, input.toCharArray())
            val actual = conversion.convert(input)
            assertEquals(
                expected,
                actual,
                "iteration $iteration diverged for input ${input.escapeForMessage()}",
            )
        }
    }

    @Test
    fun bulkSkipMatchesPerCharacterReferenceOnASingleDictionary() {
        assertEquivalentWith(
            SortedListDictionary(
                listOf(
                    "太后" to listOf("太後"),
                    "头发" to listOf("頭髮"),
                    "干燥" to listOf("乾燥"),
                    "鼠标" to listOf("滑鼠"),
                    "干" to listOf("乾", "干"),
                    "后" to listOf("後"),
                    "发" to listOf("發"),
                    "里" to listOf("裏"),
                ),
            ),
        )
    }

    @Test
    fun bulkSkipMatchesPerCharacterReferenceOnAGroup() {
        assertEquivalentWith(
            DictGroup(
                listOf(
                    SortedListDictionary(
                        listOf("太后" to listOf("太後"), "头发" to listOf("頭髮")),
                    ),
                    SortedListDictionary(
                        listOf("干" to listOf("乾", "干"), "发" to listOf("發")),
                    ),
                ),
                GroupMatchPolicy.SHORT_CIRCUIT,
            ),
        )
    }

    /** Renders control characters and surrogates readably in failure output. */
    private fun String.escapeForMessage(): String =
        buildString {
            for (ch in this@escapeForMessage) {
                if (ch.code < 0x20 || ch.isSurrogate()) {
                    append("\\u")
                    val hex = ch.code.toString(16).uppercase()
                    repeat(4 - hex.length) { append('0') }
                    append(hex)
                } else {
                    append(ch)
                }
            }
        }
}
