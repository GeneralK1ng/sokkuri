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
import kotlin.test.assertTrue

/**
 * The Utf8 equivalence contract: every Utf8 primitive
 * must agree with its UTF-16 reference in [Utf] / `decodeToString` on all
 * valid input — exhaustively over a small mixed-width alphabet (including
 * astral pairs and truncation-mid-pair windows), randomly over a wide
 * code-point distribution, plus the pinned total-behavior on malformed
 * bytes (U+FFFD advancing one byte, so corrupt files degrade instead of
 * crashing). This file is the gate that lets `.sok` retrieval compare raw
 * UTF-8 bytes against the input instead of decoded strings.
 */
class Utf8Test {

    /**
     * Every string up to length 3 over an alphabet covering 1-, 2-, 3-, and
     * 4-byte encodings plus an IDS operator, crossed with every code-unit
     * truncation of every window — truncations inside surrogate pairs yield
     * unpaired surrogates in the window, the lenient-traversal path.
     */
    @Test
    fun exhaustiveSmallAlphabetMatchesUtfReference() {
        val alphabet = intArrayOf(0x61, 0xE9, 0x4E01, 0x2FF0, 0x10000)
        val strings = buildList {
            add("")
            for (a in alphabet) {
                add(stringOf(intArrayOf(a)))
                for (b in alphabet) {
                    add(stringOf(intArrayOf(a, b)))
                    for (c in alphabet) {
                        add(stringOf(intArrayOf(a, b, c)))
                    }
                }
            }
        }.distinct()

        for (region in strings) {
            val bytes = region.encodeToByteArray()
            assertEquals(region.length, Utf8.utf16LengthOf(bytes, 0, bytes.size), "utf16LengthOf: $region")
            val out = StringBuilder()
            Utf8.decodeAppend(bytes, 0, bytes.size, out)
            assertEquals(region, out.toString(), "decodeAppend: $region")
            if (region.isNotEmpty()) {
                assertEquals(
                    Utf.codePointAt(region, 0),
                    Utf8.firstCodePointAt(bytes, 0, bytes.size),
                    "firstCodePointAt: $region",
                )
            }

            for (other in strings) {
                val otherChars = other.toCharArray()
                for (truncatedEnd in 0..other.length) {
                    assertEquals(
                        Utf.compareByCodePoint(region, other.substring(0, truncatedEnd)),
                        Utf8.compareRegionToWindow(bytes, 0, bytes.size, otherChars, 0, truncatedEnd),
                        "compareRegionToWindow(region=$region, window=${other.substring(0, truncatedEnd)})",
                    )
                }
            }
        }
    }

    /**
     * Seeded random sweep over a wide code-point distribution (ASCII,
     * Latin-1, CJK, IDS operators, supplementary planes, and boundary
     * values such as U+FFFF / U+10FFFF): 20k region/window pairs must all
     * agree with the reference comparator and decoder.
     */
    @Test
    fun randomStringsMatchUtfReference() {
        val random = Random(1234)
        repeat(20_000) {
            val region = randomString(random)
            val window = randomString(random)
            val end = random.nextInt(window.length + 1)
            val truncated = window.substring(0, end)

            val bytes = region.encodeToByteArray()
            val chars = window.toCharArray()

            assertEquals(
                Utf.compareByCodePoint(region, truncated),
                Utf8.compareRegionToWindow(bytes, 0, bytes.size, chars, 0, end),
                "compare(region=$region, window=$truncated)",
            )
            val out = StringBuilder()
            Utf8.decodeAppend(bytes, 0, bytes.size, out)
            // Numeric comparison: a char-level diff in the failure message
            // is invisible for noncharacter/boundary code points.
            assertEquals(
                region.toCharArray().toList().map { it.code },
                out.toString().toCharArray().toList().map { it.code },
                "decodeAppend(region=$region)",
            )
            assertEquals(region.length, Utf8.utf16LengthOf(bytes, 0, bytes.size), "utf16LengthOf($region)")
        }
    }

    /**
     * Malformed bytes stay total: U+FFFD per malformed unit, advancing one
     * byte, so a corrupt blob degrades to replacement characters instead of
     * throwing past the engine. Exact replacement semantics beyond that are
     * undefined per the trust boundary — only this total behavior is
     * pinned, matching the guarantee today's `decodeToString` path gives.
     */
    @Test
    fun malformedInputDecodesAsReplacementAndStaysTotal() {
        // Continuation byte as lead, overlong 2-byte, overlong 3-byte,
        // encoded surrogate, value beyond U+10FFFF, truncated sequences.
        val malformed = listOf(
            byteArrayOf(0x80.toByte()) to 1,
            byteArrayOf(0xC0.toByte(), 0x80.toByte()) to 2,
            byteArrayOf(0xC2.toByte()) to 1,
            byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte()) to 3,
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()) to 3,
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()) to 4,
            byteArrayOf(0xF0.toByte(), 0x9F.toByte()) to 2,
            byteArrayOf(0xFF.toByte()) to 1,
        )
        for ((bytes, replacements) in malformed) {
            val out = StringBuilder()
            Utf8.decodeAppend(bytes, 0, bytes.size, out)
            assertEquals(replacements, out.length, "replacement count for ${bytes.toList()}")
            assertTrue(out.all { it.code == Utf8.REPLACEMENT_CODE_POINT }, "all U+FFFD for ${bytes.toList()}")
            assertEquals(replacements, Utf8.utf16LengthOf(bytes, 0, bytes.size), "length for ${bytes.toList()}")
        }
    }

    /**
     * [Utf8.isWellFormed] against an independent reading of RFC 3629's table
     * of well-formed byte sequences (the Unicode Standard's Table 3-7):
     * exhaustively over every one- and two-byte input, over every lead byte
     * crossed with the boundary values of each continuation position, and
     * over random byte strings.
     */
    @Test
    fun isWellFormedAgreesWithTheRfc3629Table() {
        val boundaries = intArrayOf(0x00, 0x7F, 0x80, 0x8F, 0x90, 0x9F, 0xA0, 0xBF, 0xC0, 0xFF)
        for (b0 in 0..0xFF) {
            assertWellFormedness(byteArrayOf(b0.toByte()))
            for (b1 in 0..0xFF) {
                assertWellFormedness(byteArrayOf(b0.toByte(), b1.toByte()))
            }
            for (b1 in boundaries) {
                for (b2 in boundaries) {
                    assertWellFormedness(byteArrayOf(b0.toByte(), b1.toByte(), b2.toByte()))
                    for (b3 in boundaries) {
                        assertWellFormedness(byteArrayOf(b0.toByte(), b1.toByte(), b2.toByte(), b3.toByte()))
                    }
                }
            }
        }
        val random = Random(20261016)
        repeat(20_000) {
            assertWellFormedness(ByteArray(random.nextInt(0, 9)) { random.nextInt(0x100).toByte() })
        }
    }

    /** A region inside a larger buffer is judged by its own bytes only. */
    @Test
    fun isWellFormedJudgesOnlyTheRegion() {
        val bytes = byteArrayOf(0xE4.toByte(), 0xB8.toByte(), 0x80.toByte(), 0x61)
        assertTrue(Utf8.isWellFormed(bytes, 0, 3))
        assertTrue(Utf8.isWellFormed(bytes, 3, 1))
        assertTrue(Utf8.isWellFormed(bytes, 0, 0))
        assertEquals(false, Utf8.isWellFormed(bytes, 0, 2), "a sequence truncated by the region end")
        assertEquals(false, Utf8.isWellFormed(bytes, 1, 3), "a region starting mid-sequence")
    }

    /**
     * On well-formed input, [Utf8.wellFormedCodePointAt] decodes every
     * scalar value: the whole BMP outside the surrogates, every 61st
     * supplementary code point, and the plane edges.
     */
    @Test
    fun wellFormedCodePointAtDecodesEveryScalarValue() {
        val codePoints = (0 until 0xD800) + (0xE000 until 0x10000) +
            (0x10000..0x10FFFF step 61) + listOf(0x10000, 0x1FFFF, 0x20000, 0x10FFFF)
        for (codePoint in codePoints) {
            val bytes = stringOf(intArrayOf(codePoint, 'z'.code)).encodeToByteArray()
            assertTrue(Utf8.isWellFormed(bytes, 0, bytes.size), "isWellFormed U+${codePoint.toString(16)}")
            assertEquals(codePoint, Utf8.wellFormedCodePointAt(bytes, 0), "decode U+${codePoint.toString(16)}")
            assertEquals(
                Utf8.firstCodePointAt(bytes, 0, bytes.size),
                Utf8.wellFormedCodePointAt(bytes, 0),
                "agrees with firstCodePointAt at U+${codePoint.toString(16)}",
            )
            assertEquals('z'.code, Utf8.wellFormedCodePointAt(bytes, bytes.size - 1), "next sequence")
        }
    }

    private fun assertWellFormedness(bytes: ByteArray) {
        assertEquals(
            isWellFormedReference(bytes),
            Utf8.isWellFormed(bytes, 0, bytes.size),
            "isWellFormed(${bytes.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }})",
        )
    }

    /** Well-formed UTF-8, read straight off RFC 3629's byte-range table. */
    private fun isWellFormedReference(bytes: ByteArray): Boolean {
        val b = bytes.map { it.toInt() and 0xFF }
        var i = 0
        while (i < b.size) {
            val ranges: List<IntRange> = when (b[i]) {
                in 0x00..0x7F -> emptyList()
                in 0xC2..0xDF -> listOf(0x80..0xBF)
                0xE0 -> listOf(0xA0..0xBF, 0x80..0xBF)
                in 0xE1..0xEC, 0xEE, 0xEF -> listOf(0x80..0xBF, 0x80..0xBF)
                0xED -> listOf(0x80..0x9F, 0x80..0xBF)
                0xF0 -> listOf(0x90..0xBF, 0x80..0xBF, 0x80..0xBF)
                in 0xF1..0xF3 -> listOf(0x80..0xBF, 0x80..0xBF, 0x80..0xBF)
                0xF4 -> listOf(0x80..0x8F, 0x80..0xBF, 0x80..0xBF)
                else -> return false
            }
            for ((k, range) in ranges.withIndex()) {
                val at = i + 1 + k
                if (at >= b.size || b[at] !in range) return false
            }
            i += 1 + ranges.size
        }
        return true
    }

    /**
     * An empty region behaves exactly like the empty string against the
     * reference functions: compares by the window's remaining length,
     * decodes to nothing.
     */
    @Test
    fun emptyRegionMatchesEmptyStringSemantics() {
        val empty = ByteArray(0)
        val chars = "中a".toCharArray()

        assertEquals(0, Utf8.compareRegionToWindow(empty, 0, 0, chars, 0, 0))
        assertTrue(Utf8.compareRegionToWindow(empty, 0, 0, chars, 0, 2) < 0)
        assertEquals(0, Utf8.utf16LengthOf(empty, 0, 0))
        val out = StringBuilder("x")
        Utf8.decodeAppend(empty, 0, 0, out)
        assertEquals("x", out.toString())
        assertEquals(Utf8.REPLACEMENT_CODE_POINT, Utf8.firstCodePointAt(empty, 0, 0))
    }

    /** Builds a string from code points, splitting supplementary ones into surrogate pairs. */
    private fun stringOf(codePoints: IntArray): String {
        val builder = StringBuilder()
        for (codePoint in codePoints) {
            if (codePoint < 0x10000) {
                builder.append(codePoint.toChar())
            } else {
                val value = codePoint - 0x10000
                builder.append((0xD800 + (value ushr 10)).toChar())
                builder.append((0xDC00 + (value and 0x3FF)).toChar())
            }
        }
        return builder.toString()
    }

    /** Random string of 0–12 code points over the risk-table distribution. */
    private fun randomString(random: Random): String {
        val count = random.nextInt(13)
        val codePoints = IntArray(count) { randomCodePoint(random) }
        return stringOf(codePoints)
    }

    private fun randomCodePoint(random: Random): Int = when (val bucket = random.nextInt(100)) {
        in 0..24 -> random.nextInt(0x80)
        in 25..39 -> 0x80 + random.nextInt(0x700)
        // CJK Unified Ideographs only: the next range up (toward 0xEE00)
        // crosses the surrogate gap, which valid UTF-8 keys never contain.
        in 40..69 -> 0x4E00 + random.nextInt(0x5200)
        in 70..74 -> 0x2FF0 + random.nextInt(16) // IDS operators
        in 75..94 -> 0x10000 + random.nextInt(0x100000)
        else -> BOUNDARY_CODE_POINTS[random.nextInt(BOUNDARY_CODE_POINTS.size)]
    }

    private companion object {
        /** Values at encoding boundaries: noncharacters, plane edges, BMP edges. */
        val BOUNDARY_CODE_POINTS: IntArray = intArrayOf(
            0xFFFF, 0xFFFD, 0xFFFE, 0x10000, 0x10FFFF, 0xD7FF, 0xE000, 0x303F,
        )
    }
}
