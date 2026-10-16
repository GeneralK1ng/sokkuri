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
import kotlin.test.assertFailsWith

/**
 * The parity-critical group-policy semantics (OpenCC `DictGroupTest` /
 * `PrefixMatchTest`): a shorter prefix from an earlier child wins under
 * SHORT_CIRCUIT; the longest prefix wins under UNION.
 */
class DictGroupTest {

    private fun dict(vararg entries: Pair<String, List<String>>) =
        SortedListDictionary(entries.toList())

    private val alphabet = listOf("a", "b", "发", "髮", "𠀀")

    /** A random lexicon over [alphabet]: shared prefixes, a supplementary-plane character. */
    private fun randomDictionary(random: Random): SortedListDictionary {
        val keys = HashSet<String>()
        repeat(random.nextInt(1, 12)) {
            keys += buildString { repeat(random.nextInt(1, 4)) { append(alphabet.random(random)) } }
        }
        return SortedListDictionary(
            keys.sortedWith { a, b -> Utf.compareByCodePoint(a, b) }.map { it to listOf("<$it>") },
        )
    }

    /** A random group of up to three children under a random policy, nesting groups to [depth]. */
    private fun randomGroup(random: Random, depth: Int): DictGroup {
        val children = List(random.nextInt(1, 4)) {
            if (depth > 0 && random.nextInt(3) == 0) randomGroup(random, depth - 1) else randomDictionary(random)
        }
        return DictGroup(children, GroupMatchPolicy.entries.random(random))
    }

    private fun randomText(random: Random): String =
        buildString { repeat(random.nextInt(0, 7)) { append((alphabet + "z").random(random)) } }

    @Test
    fun shortCircuit_firstChildWithAnyMatchWins() {
        val earlier = dict("ab" to listOf("X"))
        val later = dict("abc" to listOf("Y"))
        val group = DictGroup(listOf(earlier, later), GroupMatchPolicy.SHORT_CIRCUIT)

        val chars = "abcd".toCharArray()
        val hit = group.matchPrefix(chars, 0, 4)
        assertEquals(2, hit!!.length)
        assertEquals("X", hit.value)
    }

    @Test
    fun union_longestMatchAcrossChildrenWins() {
        val earlier = dict("ab" to listOf("X"))
        val later = dict("abc" to listOf("Y"))
        val group = DictGroup(listOf(earlier, later), GroupMatchPolicy.UNION)

        val chars = "abcd".toCharArray()
        val hit = group.matchPrefix(chars, 0, 4)
        assertEquals(3, hit!!.length)
        assertEquals("Y", hit.value)
    }

    @Test
    fun union_equalLengthTie_breaksByChildOrder() {
        val first = dict("ab" to listOf("X"))
        val second = dict("ab" to listOf("Z"))
        val group = DictGroup(listOf(first, second), GroupMatchPolicy.UNION)

        val hit = group.matchPrefix("ab".toCharArray(), 0, 2)
        assertEquals("X", hit!!.value)
    }

    @Test
    fun exactMatch_usesGroupOrderInBothPolicies() {
        val first = dict("ab" to listOf("X"))
        val second = dict("ab" to listOf("Z"))
        for (policy in GroupMatchPolicy.entries) {
            val group = DictGroup(listOf(first, second), policy)
            assertEquals(listOf("X"), group.matchExact("ab")!!.candidates)
        }
    }

    @Test
    fun mayStartKey_unionsChildren() {
        val a = dict("猫" to listOf("cat"))
        val b = dict("狗" to listOf("dog"))
        val group = DictGroup(listOf(a, b), GroupMatchPolicy.UNION)
        assertEquals(true, group.mayStartKey('猫'.code))
        assertEquals(true, group.mayStartKey('狗'.code))
        assertEquals(false, group.mayStartKey('x'.code))
    }

    @Test
    fun matchLength_agreesWithMatchPrefixUnderBothPolicies() {
        val random = Random(20261015)
        repeat(150) { round ->
            val group = randomGroup(random, depth = 2)
            repeat(15) {
                val text = randomText(random)
                val chars = text.toCharArray()
                for (start in 0..chars.size) {
                    for (end in start..chars.size) {
                        assertEquals(
                            group.matchPrefix(chars, start, end)?.length ?: -1,
                            group.matchLength(chars, start, end),
                            "round $round, window [$start, $end) of ${text.toList()}",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun keyStarts_unionsTheChildrenAndAgreesWithMayStartKey() {
        val random = Random(20261016)
        val probes = (alphabet + listOf("z", "\uD800", "\uFFFF")).map { Utf.codePointAt(it, 0) }
        repeat(150) { round ->
            val children = List(random.nextInt(1, 4)) {
                if (random.nextInt(3) == 0) randomGroup(random, depth = 1) else randomDictionary(random)
            }
            val group = DictGroup(children, GroupMatchPolicy.entries.random(random))
            val expected = probes.filter { probe -> children.any { it.mayStartKey(probe) } }
            val keyStarts = group.keyStarts()!!
            assertEquals(expected, probes.filter { group.mayStartKey(it) }, "mayStartKey, round $round")
            assertEquals(expected, probes.filter { it in keyStarts }, "keyStarts membership, round $round")
            assertEquals(
                children.flatMap { it.keyStarts()!!.toIntArray().asList() }.distinct().sorted(),
                keyStarts.toIntArray().toList(),
                "keyStarts members, round $round",
            )
        }
    }

    @Test
    fun keyStarts_isUnknownWhenOneChildCannotEnumerate() {
        val opaque = object : Dictionary by dict("狗" to listOf("dog")) {
            override fun keyStarts(): CodePointSet? = null
        }
        val group = DictGroup(listOf(dict("猫" to listOf("cat")), opaque), GroupMatchPolicy.UNION)
        assertEquals(null, group.keyStarts())
        assertEquals(true, group.mayStartKey('猫'.code))
        assertEquals(true, group.mayStartKey('狗'.code))
        assertEquals(false, group.mayStartKey('x'.code))
    }

    @Test
    fun emptyGroupIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DictGroup(emptyList(), GroupMatchPolicy.SHORT_CIRCUIT)
        }
    }
}
