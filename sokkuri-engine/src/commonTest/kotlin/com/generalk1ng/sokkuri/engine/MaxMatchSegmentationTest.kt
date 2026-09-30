@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)
package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.engine.MaxMatchSegmentation
import com.generalk1ng.sokkuri.engine.SortedListDictionary
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
