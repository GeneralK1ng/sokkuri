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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SegmentReuse] must be invisible: a converter whose segmentation is fused
 * with stage 1 produces exactly what segmenting first and converting each
 * segment through the chain produces, the path [Converter.SingleStage.inspect]
 * keeps.
 */
class SegmentReuseTest {

    private fun leaf(vararg entries: Pair<String, String>): SortedListDictionary =
        SortedListDictionary(entries.map { (key, value) -> key to listOf(value) })

    private fun shortCircuit(vararg members: Dictionary): DictGroup =
        DictGroup(members.toList(), GroupMatchPolicy.SHORT_CIRCUIT)

    private fun union(vararg members: Dictionary): DictGroup =
        DictGroup(members.toList(), GroupMatchPolicy.UNION)

    /** Segmentation first, then the chain over each segment on its own. */
    private fun reference(segmentation: Segmentation, chain: ConversionChain, input: String): String {
        val chars = input.toCharArray()
        val out = StringBuilder()
        for (range in segmentation.segment(chars)) chain.appendConverted(chars, range.first, range.last + 1, out)
        return out.toString()
    }

    private fun assertSameAsReference(segmentation: Segmentation, chain: ConversionChain, input: String) {
        val converter = Converter.SingleStage(segmentation, chain)
        val expected = reference(segmentation, chain, input)
        assertEquals(expected, converter.convert(input), "convert, input ${input.escaped()}")
        assertEquals(expected, converter.inspect(input).output, "inspect, input ${input.escaped()}")
    }

    // The shapes the fused path accepts.

    private val phrasesA = leaf("头发" to "頭髮", "干燥" to "乾燥")
    private val phrasesB = leaf("鼠标" to "滑鼠")
    private val characters = leaf("干" to "幹", "发" to "發")

    @Test
    fun appliesWhenStageOneBeginsWithAGroupBuiltAlike() {
        // The s2tw shape: the config layer builds the segmentation's union
        // and stage 1's inner union separately, over the same cached leaves.
        val segmentation = MaxMatchSegmentation(union(phrasesA, phrasesB))
        val chain = ConversionChain(listOf(Conversion(shortCircuit(union(phrasesA, phrasesB), characters))))
        assertNotNull(SegmentReuse.of(segmentation, chain))
    }

    @Test
    fun appliesWhenStageOneIsTheSegmentationDictionary() {
        val dict = union(phrasesA, phrasesB)
        assertNotNull(SegmentReuse.of(MaxMatchSegmentation(dict), ConversionChain(listOf(Conversion(dict)))))
        assertNotNull(
            SegmentReuse.of(MaxMatchSegmentation(phrasesA), ConversionChain(listOf(Conversion(shortCircuit(phrasesA))))),
        )
    }

    @Test
    fun doesNotApplyToAnyOtherShape() {
        val segmentation = MaxMatchSegmentation(union(phrasesA, phrasesB))
        fun firstStage(dict: Dictionary) = ConversionChain(listOf(Conversion(dict)))

        // Equal content in another instance: leaves are compared by identity.
        val copyOfA = leaf("头发" to "頭髮", "干燥" to "乾燥")
        assertNull(SegmentReuse.of(segmentation, firstStage(shortCircuit(union(copyOfA, phrasesB), characters))))
        // Another policy, another order, another member count.
        assertNull(SegmentReuse.of(segmentation, firstStage(shortCircuit(shortCircuit(phrasesA, phrasesB), characters))))
        assertNull(SegmentReuse.of(segmentation, firstStage(shortCircuit(union(phrasesB, phrasesA), characters))))
        assertNull(SegmentReuse.of(segmentation, firstStage(shortCircuit(union(phrasesA), characters))))
        // The segmentation dictionary is not the first member, or the group is a union.
        assertNull(SegmentReuse.of(segmentation, firstStage(shortCircuit(characters, union(phrasesA, phrasesB)))))
        assertNull(SegmentReuse.of(segmentation, firstStage(union(union(phrasesA, phrasesB), characters))))
        // No segmentation, or no stage.
        assertNull(SegmentReuse.of(null, firstStage(union(phrasesA, phrasesB))))
        assertNull(SegmentReuse.of(segmentation, ConversionChain(emptyList())))
    }

    @Test
    fun sameMatchesComparesGroupsByStructureAndLeavesByIdentity() {
        assertTrue(sameMatches(phrasesA, phrasesA))
        assertFalse(sameMatches(phrasesA, leaf("头发" to "頭髮", "干燥" to "乾燥")))
        assertTrue(sameMatches(shortCircuit(union(phrasesA, phrasesB), characters), shortCircuit(union(phrasesA, phrasesB), characters)))
        assertFalse(sameMatches(union(phrasesA, phrasesB), shortCircuit(phrasesA, phrasesB)))
        assertFalse(sameMatches(union(phrasesA, phrasesB), union(phrasesA, phrasesB, characters)))
        assertFalse(sameMatches(union(phrasesA), phrasesA))
    }

    // Conversion through the fused path.

    @Test
    fun matchedSegmentsAndPlainRunsConvertAsBefore() {
        val segmentation = MaxMatchSegmentation(union(phrasesA, phrasesB))
        val chain = ConversionChain(
            listOf(
                Conversion(shortCircuit(union(phrasesA, phrasesB), characters)),
                Conversion(leaf("頭髮" to "头髮", "幹" to "干")),
            ),
        )
        assertNotNull(SegmentReuse.of(segmentation, chain))
        for (input in listOf("", "干", "头发干燥", "鼠标干了，头发也干了", "plain ascii", "干发🎉发干\uD800")) {
            assertSameAsReference(segmentation, chain, input)
        }
        assertEquals("头髮乾燥，干發", Converter.SingleStage(segmentation, chain).convert("头发干燥，干发"))
    }

    @Test
    fun aRunHoldingAnIdsOperatorKeepsTheWholeFirstStage() {
        // Segmentation consumes ⿰干发 whole, so the run is [0, 3) and the
        // segmentation dictionary was never asked about 干. Stage 1's second
        // member matches the operator alone, which moves stage 1 onto 干, where
        // the segmentation dictionary matches: converting the run with that
        // member only would leave 干 unconverted.
        val phrases = leaf("干" to "乾")
        val segmentation = MaxMatchSegmentation(phrases)
        val chain = ConversionChain(listOf(Conversion(shortCircuit(phrases, leaf("⿰" to "[op]")))))
        assertNotNull(SegmentReuse.of(segmentation, chain))
        assertEquals(listOf(0..<3), segmentation.segment("⿰干发".toCharArray()))
        assertEquals("[op]乾发", Converter.SingleStage(segmentation, chain).convert("⿰干发"))
        assertSameAsReference(segmentation, chain, "⿰干发")
        assertSameAsReference(segmentation, chain, "x⿰干发干")
    }

    @Test
    fun aKeyReachingIntoAnIdsKeepsTheWholeFirstStage() {
        // The run x⿰干发 holds an operator although it starts elsewhere; the
        // second member's key x⿰ carries stage 1 into the IDS, onto 干.
        val phrases = leaf("干" to "乾")
        val segmentation = MaxMatchSegmentation(phrases)
        val chain = ConversionChain(listOf(Conversion(shortCircuit(phrases, leaf("x⿰" to "X")))))
        assertEquals("X乾发", Converter.SingleStage(segmentation, chain).convert("x⿰干发"))
        assertSameAsReference(segmentation, chain, "x⿰干发")
    }

    // Differential fuzz over random dictionaries, shapes and texts.

    /** Code points the random keys, values and texts are made of. */
    private val alphabet = listOf("a", "b", "c", "d", "⿰", "⿾", "🎉", "\uD800")

    private fun randomText(random: Random, maxCodePoints: Int): String =
        buildString { repeat(random.nextInt(0, maxCodePoints + 1)) { append(alphabet[random.nextInt(alphabet.size)]) } }

    private fun randomLeaf(random: Random): SortedListDictionary {
        val entries = LinkedHashMap<String, String>()
        repeat(random.nextInt(1, 7)) {
            val key = randomText(random, 3)
            if (key.isNotEmpty()) entries[key] = randomText(random, 3)
        }
        if (entries.isEmpty()) entries["a"] = "b"
        return SortedListDictionary(entries.map { (key, value) -> key to listOf(value) })
    }

    private fun randomPolicy(random: Random): GroupMatchPolicy =
        if (random.nextBoolean()) GroupMatchPolicy.UNION else GroupMatchPolicy.SHORT_CIRCUIT

    /** A leaf, or a group of one to three leaves. */
    private fun randomDictionary(random: Random): Dictionary =
        if (random.nextInt(3) == 0) {
            randomLeaf(random)
        } else {
            DictGroup(List(random.nextInt(1, 4)) { randomLeaf(random) }, randomPolicy(random))
        }

    /** [dict] rebuilt over the same leaves, as the config layer rebuilds groups. */
    private fun rebuilt(dict: Dictionary): Dictionary =
        if (dict is DictGroup) DictGroup(dict.members.map(::rebuilt), dict.policy) else dict

    /**
     * [dict] changed so that it is no longer alike: another policy, another
     * member order, a member dropped or added, or a leaf with equal content.
     * Some of these still match alike; the fused path must not be taken for
     * any of them, and the converter must still agree with the reference.
     */
    private fun perturbed(dict: Dictionary, random: Random): Dictionary {
        if (dict !is DictGroup) {
            return if (random.nextBoolean()) DictGroup(listOf(dict), GroupMatchPolicy.UNION) else randomLeaf(random)
        }
        val members = dict.members.toMutableList()
        when (random.nextInt(4)) {
            0 -> return DictGroup(members, if (dict.policy == GroupMatchPolicy.UNION) GroupMatchPolicy.SHORT_CIRCUIT else GroupMatchPolicy.UNION)
            1 -> if (members.size > 1) members.reverse() else members.add(randomLeaf(random))
            2 -> if (members.size > 1) members.removeAt(members.lastIndex) else members.add(randomLeaf(random))
            else -> members.add(randomLeaf(random))
        }
        return DictGroup(members, dict.policy)
    }

    @Test
    fun fusedConversionMatchesTheReferenceOnRandomDictionaries() {
        val random = Random(20261116)
        var fused = 0
        repeat(400) { iteration ->
            val segmentationDict = randomDictionary(random)
            val alike = random.nextInt(4) != 0
            val head = if (alike) rebuilt(segmentationDict) else perturbed(segmentationDict, random)
            val firstDict = when (random.nextInt(4)) {
                0 -> head
                1 -> shortCircuit(head)
                2 -> shortCircuit(head, randomDictionary(random))
                else -> shortCircuit(head, randomLeaf(random), randomDictionary(random))
            }
            val stages = listOf(Conversion(firstDict)) + List(random.nextInt(0, 3)) { Conversion(randomDictionary(random)) }
            val segmentation = MaxMatchSegmentation(segmentationDict)
            val chain = ConversionChain(stages)

            val reuse = SegmentReuse.of(segmentation, chain)
            if (alike) {
                assertNotNull(reuse, "iteration $iteration: an alike first member must take the fused path")
                fused += 1
            }
            if (reuse != null) assertTrue(alike, "iteration $iteration: the fused path taken for a perturbed shape")
            repeat(15) { assertSameAsReference(segmentation, chain, randomText(random, 16)) }
        }
        assertTrue(fused > 250, "only $fused of 400 iterations took the fused path")
    }

    /** Renders surrogates readably in failure output. */
    private fun String.escaped(): String =
        buildString {
            for (ch in this@escaped) {
                if (ch.isSurrogate()) append("\\u").append(ch.code.toString(16).uppercase()) else append(ch)
            }
        }
}
