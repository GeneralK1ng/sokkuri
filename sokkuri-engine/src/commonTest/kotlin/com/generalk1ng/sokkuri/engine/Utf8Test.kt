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
 * The §3.2 equivalence contract of m3-string-view.md: every Utf8 primitive
 * must agree with its UTF-16 reference in [Utf] / `decodeToString` on all
 * valid input — exhaustively over a small mixed-width alphabet (including
 * astral pairs and truncation-mid-pair windows), randomly over a wide
 * code-point distribution, plus the pinned total-behavior on malformed
 * bytes (U+FFFD advancing one byte, so corrupt files degrade instead of
 * crashing). This file is the gate that lets step 2 trust the byte path.
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
                    assertEquals(
                        Utf.startsWithAt(otherChars, 0, truncatedEnd, region),
                        Utf8.startsWithRegionAt(bytes, 0, bytes.size, otherChars, 0, truncatedEnd),
                        "startsWithRegionAt(region=$region, window=${other.substring(0, truncatedEnd)})",
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
            assertEquals(
                Utf.startsWithAt(chars, 0, end, region),
                Utf8.startsWithRegionAt(bytes, 0, bytes.size, chars, 0, end),
                "startsWith(region=$region, window=$truncated)",
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
     * undefined per the §3.2 trust boundary — only this total behavior is
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
     * An empty region behaves exactly like the empty string against the
     * reference functions: prefix of everything, compares by the window's
     * remaining length, decodes to nothing.
     */
    @Test
    fun emptyRegionMatchesEmptyStringSemantics() {
        val empty = ByteArray(0)
        val chars = "中a".toCharArray()

        assertEquals(0, Utf8.compareRegionToWindow(empty, 0, 0, chars, 0, 0))
        assertTrue(Utf8.compareRegionToWindow(empty, 0, 0, chars, 0, 2) < 0)
        assertTrue(Utf8.startsWithRegionAt(empty, 0, 0, chars, 1, 2))
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

    /** Random string of 0–12 code points over the m3 risk-table distribution. */
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
