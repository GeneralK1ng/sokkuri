@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)
package com.generalk1ng.sokkuri.engine

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
    fun emptyGroupIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            DictGroup(emptyList(), GroupMatchPolicy.SHORT_CIRCUIT)
        }
    }
}
