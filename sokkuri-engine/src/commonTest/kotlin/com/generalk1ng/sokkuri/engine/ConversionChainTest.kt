@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)
package com.generalk1ng.sokkuri.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/** Multi-stage chain behavior (OpenCC `ConversionChainTest` port). */
class ConversionChainTest {

    private fun conversionOf(vararg entries: Pair<String, String>): Conversion =
        Conversion(SortedListDictionary(entries.map { (k, v) -> k to listOf(v) }))

    @Test
    fun stagesApplyInOrder() {
        // Deterministic pipeline: 干 → 幹, then 幹 → 乾.
        val chain = ConversionChain(
            listOf(
                conversionOf("干" to "幹"),
                conversionOf("幹" to "乾"),
            ),
        )
        val out = StringBuilder()
        chain.appendConverted("干".toCharArray(), 0, 1, out)
        assertEquals("乾", out.toString())
    }

    @Test
    fun emptyChainIsIdentity() {
        val chain = ConversionChain(emptyList())
        val out = StringBuilder()
        chain.appendConverted("不变".toCharArray(), 0, 2, out)
        assertEquals("不变", out.toString())
    }

    @Test
    fun stageSeesPreviousStageOutput() {
        // Stage 2 must convert what stage 1 emitted, per-segment.
        val chain = ConversionChain(
            listOf(
                conversionOf("发" to "發"),
                conversionOf("發型" to "HAIRSTYLE"),
            ),
        )
        val out = StringBuilder()
        chain.appendConverted("发型".toCharArray(), 0, 2, out)
        assertEquals("HAIRSTYLE", out.toString())
    }

    @Test
    fun convertWholeStringConvenience() {
        val chain = ConversionChain(listOf(conversionOf("a" to "b")))
        assertEquals("bbc", chain.convert("abc"))
        assertEquals("", chain.convert(""))
    }
}
