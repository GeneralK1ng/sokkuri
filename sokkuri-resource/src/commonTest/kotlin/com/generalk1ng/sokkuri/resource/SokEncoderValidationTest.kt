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

package com.generalk1ng.sokkuri.resource

import com.generalk1ng.sokkuri.SokkuriException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Encoder-side validation of text that UTF-8 cannot encode: an unpaired
 * surrogate in a key or a candidate is an [SokkuriException.InvalidFormat]
 * naming where it is, never a silent, platform-dependent substitute.
 */
class SokEncoderValidationTest {

    private fun assertRejected(entries: List<Pair<String, List<String>>>, detail: String) {
        val error = assertFailsWith<SokkuriException.InvalidFormat> { SokDictionaryEncoder.encode(entries) }
        assertTrue(detail in error.detail, "message '${error.detail}' should contain '$detail'")
    }

    @Test
    fun aKeyWithAnUnpairedSurrogateIsRejected() {
        val cases = listOf(
            "\uD800" to 0, // a high surrogate alone
            "\uD800a" to 0, // a high surrogate before a letter
            "\uDC00a" to 0, // a low surrogate alone
            "\uDC00\uDC00" to 0, // two low surrogates, which make no pair
            "a\uD800" to 1, // a high surrogate at the end
            "a\uDC00\uD800b" to 1, // a pair in the wrong order
            "\uD840\uDC00\uDC00" to 2, // a whole pair, then a low surrogate alone
        )
        for ((key, index) in cases) {
            assertRejected(listOf(key to listOf("x")), "has an unpaired surrogate at index $index")
        }
    }

    @Test
    fun keysALenientEncodingWouldMergeAreRejectedNotWritten() {
        // Both would come out as "a?" on the JVM: two entries, one key.
        assertRejected(listOf("a\uD800" to listOf("x"), "a\uDC00" to listOf("y")), "unpaired surrogate")
    }

    @Test
    fun aCandidateWithAnUnpairedSurrogateIsRejected() {
        assertRejected(listOf("a" to listOf("fine", "x\uDBFF")), "of 'a' has an unpaired surrogate at index 1")
    }

    @Test
    fun theMessageShowsSurrogatesEscaped() {
        assertRejected(listOf("a\uD800" to listOf("x")), "key 'a\\uD800' has")
    }

    @Test
    fun surrogatePairsAreEncoded() {
        val dictionary = SokDictionaryFormat.decode(SokDictionaryEncoder.encode(listOf("𠀀" to listOf("𠀁"))))
        assertEquals(listOf("𠀁"), dictionary.matchExact("𠀀")?.candidates)
    }
}
