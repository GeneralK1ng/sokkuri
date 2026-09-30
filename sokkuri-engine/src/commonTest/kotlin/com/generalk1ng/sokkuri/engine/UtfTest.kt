@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)
package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.engine.Utf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ports the IDS-relevant cases of OpenCC's `UTF8UtilTest` to the UTF-16
 * engine model.
 */
class UtfTest {

    @Test
    fun codePointAt_handlesBmpAndSupplementary() {
        val ascii = charArrayOf('a')
        assertEquals('a'.code, Utf.codePointAt(ascii, 0))
        assertEquals(1, Utf.charCount('a'.code))

        val cjk = "中".toCharArray()
        assertEquals(0x4E2D, Utf.codePointAt(cjk, 0))
        assertEquals(1, Utf.charCount(0x4E2D))

        val extB = "𐈀".toCharArray() // U+10200
        assertEquals(0x10200, Utf.codePointAt(extB, 0))
        assertEquals(2, Utf.charCount(0x10200))
        assertEquals(0xD800, Utf.codePointAt(extB, 0, 1)) // unpaired high surrogate: returned as-is
    }

    @Test
    fun arity_matchesOpenCcTable() {
        assertEquals(2, Utf.ideographicDescriptionOperatorArity(0x2FF0)) // ⿰
        assertEquals(2, Utf.ideographicDescriptionOperatorArity(0x2FF1)) // ⿱
        assertEquals(3, Utf.ideographicDescriptionOperatorArity(0x2FF2)) // ⿲
        assertEquals(3, Utf.ideographicDescriptionOperatorArity(0x2FF3)) // ⿳
        assertEquals(2, Utf.ideographicDescriptionOperatorArity(0x2FFD))
        assertEquals(1, Utf.ideographicDescriptionOperatorArity(0x2FFE)) // ⿾
        assertEquals(1, Utf.ideographicDescriptionOperatorArity(0x2FFF)) // ⿿
        assertEquals(0, Utf.ideographicDescriptionOperatorArity(0x3000))
        assertEquals(0, Utf.ideographicDescriptionOperatorArity('a'.code))
    }

    @Test
    fun idsSequenceLength_binaryOperator() {
        // ⿰ab — operator + two operand characters.
        assertEquals(3, Utf.ideographicDescriptionSequenceLength("⿰ab".toCharArray(), 0))
    }

    @Test
    fun idsSequenceLength_nested() {
        // ⿱⿰abc — binary over (binary over a,b) and c.
        assertEquals(5, Utf.ideographicDescriptionSequenceLength("⿱⿰abc".toCharArray(), 0))
    }

    @Test
    fun idsSequenceLength_unaryAndTernary() {
        // ⿾a — unary.
        assertEquals(2, Utf.ideographicDescriptionSequenceLength("⿾a".toCharArray(), 0))
        // ⿲abc — ternary.
        assertEquals(4, Utf.ideographicDescriptionSequenceLength("⿲abc".toCharArray(), 0))
    }

    @Test
    fun idsSequenceLength_nonOperatorReturnsZero() {
        assertEquals(0, Utf.ideographicDescriptionSequenceLength("ab".toCharArray(), 0))
        assertEquals(0, Utf.ideographicDescriptionSequenceLength("中".toCharArray(), 0))
    }

    @Test
    fun idsSequenceLength_incompleteIsZero() {
        // ⿰a — missing second operand: not a complete sequence, callers
        // consume a single character (OpenCC behavior).
        assertEquals(0, Utf.ideographicDescriptionSequenceLength("⿰a".toCharArray(), 0))
        assertEquals(0, Utf.ideographicDescriptionSequenceLength("⿰".toCharArray(), 0))
        // ⿰⿱ab — inner ⿱ complete but outer binary still missing an operand.
        assertEquals(0, Utf.ideographicDescriptionSequenceLength("⿰⿱ab".toCharArray(), 0))
    }

    @Test
    fun idsSequenceLength_excessiveDepthIsZero() {
        // 17 nested binary operators exceed kMaxIDSDepth (16).
        val deep = "⿰".repeat(17) + "a".repeat(18)
        assertEquals(0, Utf.ideographicDescriptionSequenceLength(deep.toCharArray(), 0))
    }

    @Test
    fun compareByCodePoint_ordersSupplementaryCorrectly() {
        // U+10000 < U+E000 in code-point order, though not in UTF-16 order.
        assertTrue(Utf.compareByCodePoint("\\uE000", "𐈀") < 0)
        assertEquals(0, Utf.compareByCodePoint("⿰x", "⿰x"))
        assertTrue(Utf.compareByCodePoint("ab", "abc") < 0)
    }

    @Test
    fun startsWithAt_boundsCheck() {
        val text = "abcdef".toCharArray()
        assertTrue(Utf.startsWithAt(text, 0, 6, "abc"))
        assertTrue(!Utf.startsWithAt(text, 4, 6, "abc"))
        assertTrue(!Utf.startsWithAt(text, 0, 2, "abc"))
    }
}
