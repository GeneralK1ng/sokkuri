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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CodePointSetTest {

    /** Every query [set] answers, checked against the plain sorted list of its members. */
    private fun assertAgreesWithMembers(members: List<Int>, set: CodePointSet, probes: Iterable<Int>, where: String) {
        val sorted = members.distinct().sorted()
        assertEquals(sorted.size, set.size, "size, $where")
        assertContentEquals(sorted.toIntArray(), set.toIntArray(), "toIntArray, $where")
        for (probe in probes) {
            val expected = sorted.indexOf(probe)
            assertEquals(expected >= 0, probe in set, "contains(${probe.toString(16)}), $where")
            assertEquals(expected, set.indexOf(probe), "indexOf(${probe.toString(16)}), $where")
        }
    }

    /** Members and their neighbours, plus the plane boundaries and values that are not code points. */
    private fun probesAround(members: List<Int>): Set<Int> =
        members.flatMap { listOf(it - 1, it, it + 1) }.toSet() + EDGES

    @Test
    fun emptySetHasNoMembers() {
        val empty = CodePointSet.of()
        assertEquals(CodePointSet.EMPTY, empty)
        assertAgreesWithMembers(emptyList(), empty, EDGES, "empty")
    }

    @Test
    fun membersAreDistinctAndAscendingWhateverTheInputOrder() {
        val set = CodePointSet.of(0x20000, 'b'.code, 0x4E00, 'a'.code, 0x4E00, 0x20000, 'a'.code)
        assertContentEquals(intArrayOf('a'.code, 'b'.code, 0x4E00, 0x20000), set.toIntArray())
        assertEquals(3, set.indexOf(0x20000))
    }

    @Test
    fun codePointsOutsideTheCodeSpaceAreRejected() {
        assertFailsWith<IllegalArgumentException> { CodePointSet.of(-1) }
        assertFailsWith<IllegalArgumentException> { CodePointSet.of(0x110000) }
        assertFailsWith<IllegalArgumentException> { CodePointSet.of('a'.code, Int.MIN_VALUE) }
    }

    @Test
    fun wordAndPlaneBoundariesAreExact() {
        // Bits 0 and 63 of a word, the first bit of the next word, the last BMP
        // code point, the surrogate range, and both ends of the supplementary planes.
        val members = listOf(0, 63, 64, 127, 128, 0xD800, 0xDFFF, 0xFFFF, 0x10000, 0x10FFFF)
        assertAgreesWithMembers(members, CodePointSet.of(*members.toIntArray()), probesAround(members), "boundaries")
    }

    @Test
    fun singleMembersAnywhereInThePlane() {
        for (member in listOf(0, 1, 63, 64, 0x2FF0, 0xF900, 0xFFFF, 0x10000, 0x2A6DF, 0x10FFFF)) {
            val members = listOf(member)
            assertAgreesWithMembers(members, CodePointSet.of(member), probesAround(members), "only $member")
        }
    }

    @Test
    fun queriesAgreeWithTheSortedMembersOverRandomSets() {
        val random = Random(20261016)
        repeat(300) { round ->
            val members = randomMembers(random)
            val set = CodePointSet.of(*members.toIntArray())
            val probes = probesAround(members) + List(64) { random.nextInt(-2, 0x110002) }
            assertAgreesWithMembers(members, set, probes, "round $round")
        }
    }

    @Test
    fun unionHasTheMembersOfBothSets() {
        val random = Random(20261017)
        repeat(300) { round ->
            val a = randomMembers(random)
            val b = randomMembers(random)
            val union = CodePointSet.of(*a.toIntArray()).union(CodePointSet.of(*b.toIntArray()))
            assertAgreesWithMembers(a + b, union, probesAround(a + b), "round $round")
            assertEquals(CodePointSet.of(*(a + b).toIntArray()), union, "representation, round $round")
            assertEquals(
                union,
                CodePointSet.of(*b.toIntArray()).union(CodePointSet.of(*a.toIntArray())),
                "commutative, round $round",
            )
        }
    }

    @Test
    fun unionWithTheEmptySetIsTheSameSet() {
        val set = CodePointSet.of('a'.code, 0x4E00, 0x20000)
        assertSame(set, set.union(CodePointSet.EMPTY))
        assertSame(set, CodePointSet.EMPTY.union(set))
    }

    @Test
    fun unionOfSupplementaryOnlySets() {
        val union = CodePointSet.of(0x20000, 0x20002).union(CodePointSet.of(0x20001, 0x20002))
        assertContentEquals(intArrayOf(0x20000, 0x20001, 0x20002), union.toIntArray())
        assertEquals(0, union.words.size)
    }

    @Test
    fun unionOfDisjointBitmapRanges() {
        val low = CodePointSet.of('a'.code)
        val high = CodePointSet.of(0x9FFF)
        val members = listOf('a'.code, 0x9FFF)
        assertAgreesWithMembers(members, low.union(high), probesAround(members), "low then high")
        assertAgreesWithMembers(members, high.union(low), probesAround(members), "high then low")
    }

    @Test
    fun bitmapCoversOnlyTheWordsBetweenTheLowestAndHighestBmpMember() {
        // A CJK-only set must not pay for the whole plane: this bound is the
        // reason a per-stage set stays a few KiB.
        val cjk = CodePointSet.of(0x4E00, 0x9FFF, 0x20000)
        assertEquals(0x4E00 ushr 6, cjk.firstWord)
        assertEquals((0x9FFF ushr 6) - (0x4E00 ushr 6) + 1, cjk.words.size)
        assertContentEquals(intArrayOf(0x20000), cjk.supplementary)
    }

    @Test
    fun equalityFollowsMembers() {
        assertEquals(CodePointSet.of(1, 2, 0x20000), CodePointSet.of(0x20000, 2, 1, 1))
        assertEquals(CodePointSet.of(1, 2, 0x20000).hashCode(), CodePointSet.of(0x20000, 2, 1).hashCode())
        assertNotEquals(CodePointSet.of(1, 2), CodePointSet.of(1, 3))
        assertNotEquals(CodePointSet.of(1), CodePointSet.of(1, 0x20000))
        assertFalse(CodePointSet.of(1).equals("U+0001"))
        assertTrue(CodePointSet.of(0x4E00).toString().contains("U+4E00"))
    }

    /**
     * Random members drawn from the regions the dictionaries use (ASCII, the
     * CJK blocks, the compatibility ideographs, the supplementary CJK
     * extensions) and from word boundaries, in random proportions.
     */
    private fun randomMembers(random: Random): List<Int> {
        val regions = listOf(0..0x7F, 0x3400..0x9FFF, 0xF900..0xFAFF, 0x20000..0x2A6DF, 0..0x10FFFF)
        return List(random.nextInt(0, 80)) {
            if (random.nextInt(8) == 0) {
                random.nextInt(0, 0x10000 / 64) * 64 + listOf(0, 63).random(random)
            } else {
                regions.random(random).random(random)
            }
        }
    }

    private companion object {
        val EDGES = setOf(Int.MIN_VALUE, -1, 0, 0xFFFF, 0x10000, 0x10FFFF, 0x110000, Int.MAX_VALUE)
    }
}
