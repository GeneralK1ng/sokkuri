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

/** Maximal-match segmentation behavior (OpenCC `MaxMatchSegmentationTest` port). */
class MaxMatchSegmentationTest {

    private fun dict(vararg entries: Pair<String, List<String>>) =
        SortedListDictionary(entries.toList())

    private fun segment(vararg keys: String, text: String): List<String> {
        val d = dict(*keys.map { it to listOf(it.uppercase()) }.toTypedArray())
        val chars = text.toCharArray()
        return MaxMatchSegmentation(d).segment(chars).map { chars.concatToString(it.first, it.last + 1) }
    }

    @Test
    fun dictionaryPhraseBecomesStandaloneSegment() {
        assertEquals(listOf("燕燕于飞"), segment("燕燕于飞", text = "燕燕于飞"))
    }

    @Test
    fun unmatchedRunsAccumulateBetweenMatches() {
        assertEquals(
            listOf("一只", "燕子", "飞"),
            segment("燕子", text = "一只燕子飞"),
        )
    }

    @Test
    fun mixedAsciiAndCjk() {
        assertEquals(
            listOf("hello ", "燕", " world"),
            segment("燕", text = "hello 燕 world"),
        )
    }

    @Test
    fun longestMatchWins() {
        assertEquals(
            listOf("燕燕于飞", "来"),
            segment("燕", "燕燕", "燕燕于飞", text = "燕燕于飞来"),
        )
    }

    @Test
    fun emptyInputYieldsNoSegments() {
        assertEquals(emptyList(), segment("燕", text = ""))
    }

    @Test
    fun wholeTextRunWhenNothingMatches() {
        assertEquals(listOf("什么都没匹配"), segment("燕", text = "什么都没匹配"))
    }

    @Test
    fun ideographicDescriptionSequenceEndsRunBeforeMatch() {
        // "⿰ab" is a complete IDS (not a dictionary key): it is accumulated
        // into the pending run, which is flushed when "燕" matches.
        assertEquals(listOf("⿰ab", "燕"), segment("燕", text = "⿰ab燕"))
    }
}
