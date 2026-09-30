@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)
package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.SokkuriException
import com.generalk1ng.sokkuri.engine.SortedListDictionary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract tests for the sorted-array dictionary (OpenCC `TextDict` port). */
class SortedListDictionaryTest {

    private fun dict(vararg entries: Pair<String, List<String>>): SortedListDictionary =
        SortedListDictionary(entries.toList())

    @Test
    fun matchExact_hitsAndMisses() {
        val d = dict("发" to listOf("發"), "干" to listOf("幹", "干"))
        assertEquals(listOf("發"), d.matchExact("发")?.candidates)
        assertEquals(listOf("幹", "干"), d.matchExact("干")?.candidates)
        assertNull(d.matchExact("髮"))
    }

    @Test
    fun matchPrefix_returnsLongestCandidate() {
        val d = dict("a" to listOf("A"), "ab" to listOf("AB"), "abc" to listOf("ABC"))
        val chars = "abcd".toCharArray()
        val hit = d.matchPrefix(chars, 0, 4)
        assertNotNull(hit)
        assertEquals(3, hit.length)
        assertEquals("ABC", hit.value)
    }

    @Test
    fun matchPrefix_obeysStartAndEnd() {
        val d = dict("bc" to listOf("BC"))
        val chars = "abc".toCharArray()
        assertNull(d.matchPrefix(chars, 0, 1)) // "a" — no key
        assertEquals("BC", d.matchPrefix(chars, 1, 3)?.value)
    }

    @Test
    fun candidatesPreserveOrder_firstIsDefault() {
        val d = dict("万" to listOf("萬", "万"))
        assertEquals(listOf("萬", "万"), d.matchExact("万")?.candidates)
        assertEquals("萬", d.matchPrefix("万".toCharArray(), 0, 1)?.value)
    }

    @Test
    fun unsortedInputIsSortedByCodePoint() {
        val d = dict("b" to listOf("B"), "a" to listOf("A"))
        assertEquals("A", d.matchExact("a")?.candidates?.first())
        assertEquals("B", d.matchPrefix("b".toCharArray(), 0, 1)?.value)
    }

    @Test
    fun duplicateKeysAreRejected() {
        assertFailsWith<SokkuriException.InvalidFormat> {
            dict("a" to listOf("A"), "a" to listOf("A2"))
        }
    }

    @Test
    fun emptyKeyOrCandidatesAreRejected() {
        assertFailsWith<SokkuriException.InvalidFormat> { dict("" to listOf("A")) }
        assertFailsWith<SokkuriException.InvalidFormat> { dict("a" to emptyList()) }
    }

    @Test
    fun nonBmpKeysAreSupported() {
        // Supplementary-plane key (Seal-range style), exercising the
        // code-point comparator and surrogate-pair prefix scan.
        val d = dict("𠀀" to listOf("x"))
        val chars = "𠀀b".toCharArray()
        val hit = d.matchPrefix(chars, 0, 3)
        assertNotNull(hit)
        assertEquals(2, hit.length)
        assertEquals("x", hit.value)
        assertTrue(d.mayStartKey(0x20000))
        assertTrue(!d.mayStartKey('b'.code))
    }

    @Test
    fun emptyDictionaryMatchesNothing() {
        val d = dict()
        assertEquals(0, d.maxKeyLength)
        assertNull(d.matchPrefix("anything".toCharArray(), 0, 8))
        assertFalse(d.mayStartKey('a'.code)) // empty: nothing can start a key
    }
}
