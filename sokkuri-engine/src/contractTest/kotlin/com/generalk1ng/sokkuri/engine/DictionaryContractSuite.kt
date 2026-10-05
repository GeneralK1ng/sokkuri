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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Implementation-agnostic contract suite for [Dictionary] — the retrieval
 * contract every lexicon backend must satisfy: the sorted-array reference
 * ([SortedListDictionary], exercised by [SortedListDictionaryContractTest]),
 * the `.sok` binary format in sokkuri-resource, and any future backend.
 *
 * The suite pins interface behavior only: longest-prefix retrieval, ordered
 * candidates with a defined default, [Dictionary.mayStartKey], UTF-16
 * code-unit accounting (invariant I1), code-point ordering (invariant I2),
 * and lenient handling of unpaired surrogates in the *input text*. Two
 * boundary notes:
 *
 * - Keys are kept to Unicode scalar values (no unpaired surrogates), because
 *   `.sok` encodes keys as UTF-8 and cannot represent them; leniency is a
 *   requirement on input text, not on keys.
 * - Construction concerns (duplicate keys, sorting of unsorted input, empty
 *   keys or candidate lists) are deliberately out of scope — each backend
 *   validates its own input format in its own construction tests.
 *
 * Backends run the suite by subclassing and implementing
 * [createDictionary]. Inputs are supplied in [Utf.compareByCodePoint] order:
 * ordering is a construction concern, not part of the retrieval contract.
 */
abstract class DictionaryContractSuite {

    /**
     * Creates the dictionary under test from [entries], each pair mapping a
     * key to its ordered candidates (the first candidate is the default).
     * [entries] are in code-point order with unique, non-empty keys and
     * non-empty candidate lists; construction-time validation is not
     * exercised by this suite.
     */
    protected abstract fun createDictionary(
        entries: List<Pair<String, List<String>>>,
    ): Dictionary

    /** Shorthand for building a small lexicon given in code-point order. */
    protected fun dictionaryOf(vararg entries: Pair<String, List<String>>): Dictionary =
        createDictionary(entries.toList())

    /** Shorthand for [Dictionary.matchPrefix] over a [String] window. */
    protected fun Dictionary.prefixOf(
        text: String,
        start: Int = 0,
        end: Int = text.length,
    ): PrefixMatch? = matchPrefix(text.toCharArray(), start, end)

    @Test
    fun matchExactReturnsEntryWithOrderedCandidates() {
        val d = dictionaryOf("发" to listOf("發"), "干" to listOf("幹", "干"))
        assertEquals(listOf("發"), d.matchExact("发")?.candidates)
        assertEquals(listOf("幹", "干"), d.matchExact("干")?.candidates)
    }

    @Test
    fun matchExactReturnsNullOnMiss() {
        val d = dictionaryOf("发" to listOf("發"))
        assertNull(d.matchExact("髮")) // absent key
        assertNull(d.matchExact("发型")) // longer than any stored key
        assertNull(d.matchExact("")) // empty keys can never be stored
    }

    @Test
    fun matchPrefixPrefersTheLongestKey() {
        val d = dictionaryOf("a" to listOf("A"), "ab" to listOf("AB"), "abc" to listOf("ABC"))
        assertEquals(3, d.maxKeyLength)
        val hit = assertNotNull(d.prefixOf("abcd"))
        assertEquals(3, hit.length)
        assertEquals("ABC", hit.value)
    }

    @Test
    fun matchPrefixValueIsTheDefaultCandidate() {
        val d = dictionaryOf("万" to listOf("萬", "万"))
        assertEquals("萬", d.prefixOf("万")?.value)
    }

    @Test
    fun matchPrefixResultIsConsistentWithMatchExact() {
        val d = dictionaryOf("ab" to listOf("AB1", "AB2"), "abc" to listOf("ABC"))
        val hit = assertNotNull(d.prefixOf("abcd"))
        assertEquals("abc".length, hit.length)
        assertEquals(d.matchExact("abc")?.candidates?.first(), hit.value)
    }

    @Test
    fun matchPrefixObeysWindowBounds() {
        val d = dictionaryOf("bc" to listOf("BC"))
        val chars = "abc".toCharArray()
        assertNull(d.matchPrefix(chars, 0, 1)) // window "a"
        assertEquals("BC", d.matchPrefix(chars, 1, 3)?.value) // window "bc"
        assertNull(d.matchPrefix(chars, 1, 2)) // key longer than window
        assertNull(d.matchPrefix(chars, 2, 2)) // empty window
        assertNull(d.matchPrefix(chars, 3, 3)) // empty window at the end
    }

    @Test
    fun matchPrefixNeverMatchesPartialKeys() {
        val d = dictionaryOf("abc" to listOf("ABC"))
        assertNull(d.prefixOf("ab"))
        assertNull(d.prefixOf("a"))
        assertEquals("ABC", d.prefixOf("abc")?.value)
    }

    @Test
    fun mayStartKeyReflectsKeyInitialCodePoints() {
        val d = dictionaryOf("发" to listOf("發"), "干" to listOf("幹"))
        assertTrue(d.mayStartKey('发'.code))
        assertTrue(d.mayStartKey('干'.code))
        assertFalse(d.mayStartKey('髮'.code))
        assertFalse(d.mayStartKey('a'.code))
    }

    @Test
    fun emptyDictionaryMatchesNothing() {
        val d = dictionaryOf()
        assertEquals(0, d.maxKeyLength)
        assertNull(d.matchExact("a"))
        assertNull(d.prefixOf("anything"))
        assertFalse(d.mayStartKey('a'.code))
    }

    @Test
    fun nonBmpKeysAreAccountedInCodeUnits() {
        val d = dictionaryOf("𠀀" to listOf("x"))
        assertEquals(2, d.maxKeyLength)
        assertEquals(listOf("x"), d.matchExact("𠀀")?.candidates)
        val hit = assertNotNull(d.prefixOf("𠀀b"))
        assertEquals(2, hit.length)
        assertEquals("x", hit.value)
        assertTrue(d.mayStartKey(0x20000))
        assertFalse(d.mayStartKey(0xD800)) // the pair's high surrogate alone is not a key start
    }

    @Test
    fun loneSurrogateInputIsLenientAndNeverCombines() {
        // Utf.codePointAt treats an unpaired surrogate as its own code point;
        // retrieval must not fuse it with the following BMP character.
        val d = dictionaryOf("干" to listOf("幹"))
        val text = "\uD800干"
        assertFalse(d.mayStartKey(0xD800))
        assertNull(d.prefixOf(text))
        assertEquals("幹", d.prefixOf(text, 1, 2)?.value)
    }

    @Test
    fun surrogatePairsAreNeverSplit() {
        val d = dictionaryOf("日" to listOf("v"), "𠀀" to listOf("w"))
        val hit = assertNotNull(d.prefixOf("𠀀日"))
        assertEquals(2, hit.length)
        assertEquals("w", hit.value)
        assertNull(d.prefixOf("𠀀日", 1, 3)) // mid-pair start matches nothing
        assertNull(d.prefixOf("\uD800")) // lone high surrogate is neither 日 nor 𠀀
        assertFalse(d.mayStartKey(0xDC00))
    }

    @Test
    fun idsOperatorKeysMatchOnlyAsCompleteKeys() {
        // Keys containing IDS operators are ordinary keys for this layer:
        // they match only in full. Atomic consumption of *unmatched* IDS
        // sequences is the conversion loop's job (Conversion.kt), not the
        // dictionary's — retrieval never invents a partial-key match.
        val d = dictionaryOf("⿰日月" to listOf("明"))
        val hit = assertNotNull(d.prefixOf("⿰日月光"))
        assertEquals(3, hit.length)
        assertEquals("明", hit.value)
        assertNull(d.prefixOf("⿰日")) // a proper prefix of a key is no match
        assertTrue(d.mayStartKey(0x2FF0))
        assertFalse(d.mayStartKey('日'.code))
    }
}
