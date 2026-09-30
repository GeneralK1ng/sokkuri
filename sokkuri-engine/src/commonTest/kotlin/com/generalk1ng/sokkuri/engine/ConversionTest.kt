@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)
package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.engine.Conversion
import com.generalk1ng.sokkuri.engine.DictGroup
import com.generalk1ng.sokkuri.engine.GroupMatchPolicy
import com.generalk1ng.sokkuri.engine.SortedListDictionary
import kotlin.test.Test
import kotlin.test.assertEquals

/** Core single-stage conversion behavior (OpenCC `ConversionTest` port). */
class ConversionTest {

    private fun dict(vararg entries: Pair<String, List<String>>) =
        SortedListDictionary(entries.toList())

    @Test
    fun singleCharacterReplacement() {
        val conversion = Conversion(dict("发" to listOf("發")))
        assertEquals("發动", conversion.convert("发动"))
    }

    @Test
    fun unmatchedTextPassesThrough() {
        val conversion = Conversion(dict("发" to listOf("發")))
        assertEquals("xyz", conversion.convert("xyz"))
    }

    @Test
    fun phraseGroupTakesPriorityOverCharacterDict() {
        // Mirrors the s2t structure: short_circuit[phrases, characters].
        val phrases = dict("发火" to listOf("X"))
        val characters = dict("发" to listOf("發"))
        val conversion = Conversion(
            DictGroup(listOf(phrases, characters), GroupMatchPolicy.SHORT_CIRCUIT),
        )
        assertEquals("X", conversion.convert("发火"))
        assertEquals("發", conversion.convert("发"))
        assertEquals("X型", conversion.convert("发火型"))
    }

    @Test
    fun multiCandidateUsesFirstAsDefault() {
        val conversion = Conversion(dict("干" to listOf("幹", "干", "乾")))
        assertEquals("幹", conversion.convert("干"))
    }

    @Test
    fun ideographicDescriptionSequenceStaysAtomic() {
        // A complete IDS with no dictionary key starting at its operator is
        // emitted whole (OpenCC IDS-preservation case).
        val conversion = Conversion(dict("猫" to listOf("cat")))
        assertEquals("⿰abcat", conversion.convert("⿰ab猫"))
    }

    @Test
    fun prefixMatchTakesPriorityOverIdsParsing() {
        // OpenCC tries the dictionary first: a key that literally starts with
        // an IDS operator matches like any other prefix.
        val conversion = Conversion(dict("⿰a" to listOf("X")))
        assertEquals("Xb", conversion.convert("⿰ab"))
    }

    @Test
    fun asciiRunsSkipPrefixProbes() {
        // mayStartKey=false characters are bulk-copied without lookups.
        val conversion = Conversion(dict("中" to listOf("中")))
        assertEquals("hello, world!中", conversion.convert("hello, world!中"))
    }

    @Test
    fun emptyInputIsEmpty() {
        val conversion = Conversion(dict("a" to listOf("A")))
        assertEquals("", conversion.convert(""))
    }

    @Test
    fun supplementaryPlanePassthrough() {
        val conversion = Conversion(dict("a" to listOf("A")))
        assertEquals("𐈀A𐈀", conversion.convert("𐈀a𐈀"))
    }
}
