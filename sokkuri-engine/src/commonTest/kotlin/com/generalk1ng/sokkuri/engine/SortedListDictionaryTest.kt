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

import com.generalk1ng.sokkuri.SokkuriException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Construction-semantics tests for [SortedListDictionary]: input ordering,
 * duplicate rejection, and key/value validity. Interface-level retrieval
 * behavior (longest prefix, candidates, `mayStartKey`, surrogate and IDS
 * handling) is covered by [DictionaryContractSuite], run against this class
 * by [SortedListDictionaryContractTest].
 */
class SortedListDictionaryTest {

    private fun dict(vararg entries: Pair<String, List<String>>): SortedListDictionary =
        SortedListDictionary(entries.toList())

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
}
