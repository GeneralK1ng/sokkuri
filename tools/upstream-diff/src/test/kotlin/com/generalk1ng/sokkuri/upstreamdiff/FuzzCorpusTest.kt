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

package com.generalk1ng.sokkuri.upstreamdiff

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The fuzz corpus's file format, what may enter it, and the dictionary handed to libFuzzer. */
class FuzzCorpusTest {

    @Test
    fun corpusFileRoundTripsAnyComparableInput() {
        val inputs = listOf("", "a\"b\\c", "tab\there", "ctl\u0001", "⿰钅只", "𠀀", "  spaces  ")
        val text = FuzzCorpus.format(inputs)
        assertEquals(inputs.size, text.lines().count { it.isNotEmpty() }, "one line per input")
        assertEquals(inputs, FuzzCorpus.parse(text))
        assertEquals(inputs, FuzzCorpus.parse("\n" + text + "\n\n"), "blank lines are ignored")
    }

    @Test
    fun aMissingCorpusFileIsEmpty() {
        assertEquals(emptyList(), FuzzCorpus.read(File("/nonexistent/fuzz-corpus.jsonl")))
    }

    @Test
    fun onlyWellFormedComparableBytesBecomeInputs() {
        assertEquals("干燥", decodeComparable("干燥".encodeToByteArray()))
        assertEquals("𠀀", decodeComparable("𠀀".encodeToByteArray()))
        assertNull(decodeComparable(byteArrayOf(0xE5.toByte(), 0xB9.toByte())), "truncated sequence")
        assertNull(decodeComparable(byteArrayOf(0xC0.toByte(), 0x80.toByte())), "overlong form")
        assertNull(decodeComparable(byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())), "encoded surrogate")
        assertNull(decodeComparable("a\nb".encodeToByteArray()), "line break")
        assertNull(decodeComparable("a\rb".encodeToByteArray()), "carriage return")
        assertNull(decodeComparable(byteArrayOf(0x61, 0x00)), "NUL")
    }

    @Test
    fun dictionaryEscapesEverythingButPrintableAscii() {
        assertEquals(
            "\"ab\"\n\"\\\"\\\\\"\n\"\\xE5\\xB9\\xB2\"\n\"\\x09\"\n",
            libFuzzerDictionary(listOf("ab", "\"\\", "干", "\t")),
        )
    }
}
