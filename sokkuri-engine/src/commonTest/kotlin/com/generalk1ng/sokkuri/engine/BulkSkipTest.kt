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
import kotlin.test.assertNull

/**
 * [BulkSkip] must answer exactly [stopsBulkSkip], whether it reads its stop
 * set from [Dictionary.keyStarts] or, for a dictionary that cannot enumerate
 * them, asks the dictionary; and its run must end where the loop it replaced
 * in [Conversion] and [MaxMatchSegmentation] ended.
 */
class BulkSkipTest {

    private fun dict(vararg keys: String) = SortedListDictionary(keys.map { it to listOf("<$it>") })

    /** The same dictionary, unable to enumerate its key starts: the fallback path. */
    private fun opaque(dictionary: Dictionary): Dictionary = object : Dictionary by dictionary {
        override fun keyStarts(): CodePointSet? = null
    }

    /** The loop both scan loops ran before [BulkSkip]: the reference semantics. */
    private fun referenceRunEnd(dict: Dictionary, chars: CharArray, start: Int, end: Int): Int {
        var width = Utf.ideographicDescriptionSequenceLength(chars, start, end)
        if (width == 0) width = Utf.charCount(Utf.codePointAt(chars, start, end))
        var j = start + width
        while (j < end) {
            val codePoint = Utf.codePointAt(chars, j, end)
            if (dict.stopsBulkSkip(codePoint)) break
            j += Utf.charCount(codePoint)
        }
        return j
    }

    private val curated: List<Dictionary> = listOf(
        dict("干", "发展", "𠀀"), // BMP and supplementary key starts
        dict("⿰日月", "a"), // a key that starts with an IDS operator
        dict("\uD800x", "\uDC00"), // keys that start with unpaired surrogates
        DictGroup(
            listOf(dict("发"), DictGroup(listOf(dict("髮"), dict("𠀀")), GroupMatchPolicy.UNION)),
            GroupMatchPolicy.SHORT_CIRCUIT,
        ),
    )

    @Test
    fun stopsExactlyWhereStopsBulkSkipDoesForEveryBmpCodePoint() {
        val supplementary = listOf(0x10000, 0x1F600, 0x20000, 0x20001, 0x2A6DF, 0x10FFFF)
        for ((which, base) in curated.withIndex()) {
            for (dictionary in listOf(base, opaque(base))) {
                val skip = BulkSkip(dictionary)
                for (codePoint in (0 until 0x10000) + supplementary) {
                    // 'z' is consumed first; the skip then decides on the code point after it.
                    val chars = ("z" + codePointString(codePoint)).toCharArray()
                    val expected = if (dictionary.stopsBulkSkip(codePoint)) 1 else chars.size
                    assertEquals(
                        expected,
                        skip.unmatchedRunEnd(chars, 0, chars.size),
                        "dictionary $which, keyStarts ${dictionary.keyStarts() != null}, U+${codePoint.toString(16)}",
                    )
                }
            }
        }
    }

    @Test
    fun runEndsWhereTheReplacedLoopEnded() {
        val keyAlphabet = listOf("a", "发", "髮", "𠀀", "⿰", "日")
        val textAlphabet = keyAlphabet + listOf("z", "月", "⿲", "⿾", "\uD800", "\uDC00", "𠀁")
        val random = Random(20261016)
        repeat(200) { round ->
            val keys = List(random.nextInt(1, 8)) {
                buildString { repeat(random.nextInt(1, 3)) { append(keyAlphabet.random(random)) } }
            }.distinct().sortedWith { a, b -> Utf.compareByCodePoint(a, b) }
            val base = if (random.nextBoolean()) dict(*keys.toTypedArray()) else DictGroup(
                listOf(dict(*keys.toTypedArray()), dict("月")),
                GroupMatchPolicy.entries.random(random),
            )
            for (dictionary in listOf(base, opaque(base))) {
                val skip = BulkSkip(dictionary)
                repeat(10) {
                    val text = buildString { repeat(random.nextInt(1, 12)) { append(textAlphabet.random(random)) } }
                    val chars = text.toCharArray()
                    for (start in chars.indices) {
                        for (end in start + 1..chars.size) {
                            assertEquals(
                                referenceRunEnd(dictionary, chars, start, end),
                                skip.unmatchedRunEnd(chars, start, end),
                                "round $round, keys $keys, keyStarts ${dictionary.keyStarts() != null}, " +
                                    "window [$start, $end) of ${text.toList()}",
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun skipStopsAtEveryOperatorAndConsumesAnIdsWhole() {
        for (dictionary in listOf(dict("干"), opaque(dict("干")))) {
            val skip = BulkSkip(dictionary)
            val chars = "abc⿰日月xyz干".toCharArray()
            assertEquals(3, skip.unmatchedRunEnd(chars, 0, chars.size), "stops at the operator")
            assertEquals(9, skip.unmatchedRunEnd(chars, 3, chars.size), "the IDS whole, then up to 干")
            assertEquals(chars.size, skip.unmatchedRunEnd(chars, 9, chars.size), "the key start itself is consumed")
        }
    }

    @Test
    fun aGroupThatCannotEnumerateFallsBack() {
        val group = DictGroup(listOf(dict("干"), opaque(dict("发"))), GroupMatchPolicy.UNION)
        assertNull(group.keyStarts())
        val chars = "ab发cd干".toCharArray()
        assertEquals(2, BulkSkip(group).unmatchedRunEnd(chars, 0, chars.size))
        assertEquals(5, BulkSkip(group).unmatchedRunEnd(chars, 2, chars.size))
    }

    private fun codePointString(codePoint: Int): String =
        if (codePoint < 0x10000) {
            codePoint.toChar().toString()
        } else {
            val value = codePoint - 0x10000
            charArrayOf((0xD800 + (value ushr 10)).toChar(), (0xDC00 + (value and 0x3FF)).toChar()).concatToString()
        }
}
