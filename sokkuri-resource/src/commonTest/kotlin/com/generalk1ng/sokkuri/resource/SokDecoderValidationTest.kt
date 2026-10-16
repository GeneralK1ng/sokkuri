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
import com.generalk1ng.sokkuri.engine.Utf
import com.generalk1ng.sokkuri.engine.Utf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Strict-decoder validation tests: every
 * corruption class must be rejected with [SokkuriException.InvalidFormat]
 * whose message names the offending field. Files are produced by taking a
 * known-valid encoding and mutating one field at a time.
 */
class SokDecoderValidationTest {

    private val valid: ByteArray = SokDictionaryEncoder.encode(
        listOf(
            "a" to listOf("A"),
            "ab" to listOf("AB"),
            "abc" to listOf("ABC"),
        ),
    )

    private fun corrupt(patch: ByteArray.() -> Unit): ByteArray = valid.copyOf().also(patch)

    private val entryCount: Int =
        SokFormatLayout.readU32(valid, SokFormatLayout.OFFSET_ENTRY_COUNT).toInt()

    private val valueOffsetsBase: Int =
        SokFormatLayout.HEADER_BYTES + 4 * (entryCount + 1)

    /**
     * Start of the value blob: past the header, both offset tables, the
     * valueCounts column, and the key blob. `valid` encodes keys a/ab/abc
     * with candidates A/AB/ABC, so its value blob is
     * `[u16 1]"A" [u16 2]"AB" [u16 3]"ABC"` and valueOffsets is [0, 3, 7, 12].
     */
    private val valueBlobBase: Int = valueOffsetsBase + 4 * (entryCount + 1) + entryCount +
        SokFormatLayout.readU32(valid, SokFormatLayout.OFFSET_KEY_BLOB_BYTES).toInt()

    /** Start of the key blob, `"a" "ab" "abc"` back to back, so keyOffsets is [0, 1, 3, 6]. */
    private val keyBlobBase: Int = valueOffsetsBase + 4 * (entryCount + 1) + entryCount

    /**
     * [valid] with one byte no entry refers to inserted at [blobBase], the
     * start of a blob: the blob's size field at [sizeField] and every offset
     * of the table at [offsetsBase] grow by one, so the file breaks nothing
     * but the rule that the table starts at 0.
     */
    private fun withLeadingByte(blobBase: Int, offsetsBase: Int, sizeField: Int): ByteArray {
        val bytes = valid.copyOfRange(0, blobBase) + byteArrayOf(0) + valid.copyOfRange(blobBase, valid.size)
        for (i in 0..entryCount) {
            val at = offsetsBase + i * 4
            SokFormatLayout.writeU32(bytes, at, SokFormatLayout.readU32(bytes, at) + 1)
        }
        SokFormatLayout.writeU32(bytes, sizeField, SokFormatLayout.readU32(bytes, sizeField) + 1)
        return bytes
    }

    private fun assertRejected(bytes: ByteArray, field: String) {
        val error = assertFailsWith<SokkuriException.InvalidFormat> {
            SokDictionaryFormat.decode(bytes)
        }
        assertTrue(
            field in error.detail,
            "message '${error.detail}' should name the field '$field'",
        )
    }

    @Test
    fun fileShorterThanTheHeaderIsRejected() {
        assertRejected(valid.copyOfRange(0, SokFormatLayout.HEADER_BYTES - 1), "header")
    }

    @Test
    fun fileTruncatedToADifferentSizeIsRejected() {
        // The header is intact, so the failure is the entryCount/size
        // consistency check rather than the header-length check.
        assertRejected(valid.copyOf(valid.size - 1), "entryCount")
    }

    @Test
    fun badMagicIsRejected() {
        assertRejected(corrupt { this[0] = 'X'.code.toByte() }, "magic")
    }

    @Test
    fun unsupportedVersionIsRejected() {
        assertRejected(corrupt {
            SokFormatLayout.writeU32(this, SokFormatLayout.OFFSET_VERSION, 2)
        }, "formatVersion")
    }

    @Test
    fun nonZeroFlagsAreRejected() {
        assertRejected(corrupt {
            SokFormatLayout.writeU32(this, SokFormatLayout.OFFSET_FLAGS, 1)
        }, "flags")
    }

    @Test
    fun nonZeroReservedFieldIsRejected() {
        assertRejected(corrupt {
            SokFormatLayout.writeU32(this, SokFormatLayout.OFFSET_RESERVED, 7)
        }, "reserved")
    }

    @Test
    fun entryCountInconsistentWithFileSizeIsRejected() {
        assertRejected(corrupt {
            SokFormatLayout.writeU32(this, SokFormatLayout.OFFSET_ENTRY_COUNT, 4)
        }, "entryCount")
    }

    @Test
    fun maxKeyLengthOtherThanTheLongestKeyIsRejected() {
        // The longest key, "abc", has 3 code units: one fewer would lose its
        // matches, one more is still not the length the format states.
        for (stated in listOf(2L, 4L)) {
            assertRejected(corrupt {
                SokFormatLayout.writeU32(this, SokFormatLayout.OFFSET_MAX_KEY_LENGTH, stated)
            }, "maxKeyLength is $stated, but the longest key has 3")
        }
    }

    @Test
    fun maxKeyLengthCountsUtf16CodeUnitsNotBytes() {
        // "𠀀中" is 7 bytes of UTF-8 but 3 UTF-16 code units: a surrogate pair and one more.
        val bytes = SokDictionaryEncoder.encode(listOf("𠀀中" to listOf("x"), "a" to listOf("y")))
        assertEquals(3L, SokFormatLayout.readU32(bytes, SokFormatLayout.OFFSET_MAX_KEY_LENGTH))
        assertEquals(3, SokDictionaryFormat.decode(bytes).maxKeyLength)
        SokFormatLayout.writeU32(bytes, SokFormatLayout.OFFSET_MAX_KEY_LENGTH, 7)
        assertRejected(bytes, "the longest key has 3 UTF-16 code units")
    }

    @Test
    fun nonIncreasingKeyOffsetsAreRejected() {
        val keyOffsetsBase = SokFormatLayout.HEADER_BYTES
        assertRejected(corrupt {
            SokFormatLayout.writeU32(this, keyOffsetsBase + 4, 0)
        }, "keyOffsets")
    }

    @Test
    fun keyOffsetsNotTerminatingAtTheBlobAreRejected() {
        val n = SokFormatLayout.readU32(valid, SokFormatLayout.OFFSET_ENTRY_COUNT).toInt()
        val keyOffsetsBase = SokFormatLayout.HEADER_BYTES
        assertRejected(corrupt {
            SokFormatLayout.writeU32(this, keyOffsetsBase + n * 4, 1000)
        }, "keyOffsets")
    }

    @Test
    fun keyOffsetsNotStartingAtZeroAreRejected() {
        val bytes = withLeadingByte(keyBlobBase, SokFormatLayout.HEADER_BYTES, SokFormatLayout.OFFSET_KEY_BLOB_BYTES)
        assertRejected(bytes, "keyOffsets[0]")
    }

    @Test
    fun valueOffsetsNotStartingAtZeroAreRejected() {
        val bytes = withLeadingByte(valueBlobBase, valueOffsetsBase, SokFormatLayout.OFFSET_VALUE_BLOB_BYTES)
        assertRejected(bytes, "valueOffsets[0]")
    }

    @Test
    fun nonIncreasingValueOffsetsAreRejected() {
        val n = SokFormatLayout.readU32(valid, SokFormatLayout.OFFSET_ENTRY_COUNT).toInt()
        val valueOffsetsBase = SokFormatLayout.HEADER_BYTES + 4 * (n + 1)
        assertRejected(corrupt {
            SokFormatLayout.writeU32(this, valueOffsetsBase + 4, 0)
        }, "valueOffsets")
    }

    @Test
    fun valueOffsetsNotTerminatingAtTheBlobAreRejected() {
        val n = SokFormatLayout.readU32(valid, SokFormatLayout.OFFSET_ENTRY_COUNT).toInt()
        val valueOffsetsBase = SokFormatLayout.HEADER_BYTES + 4 * (n + 1)
        val valueBlobBytes = SokFormatLayout.readU32(valid, SokFormatLayout.OFFSET_VALUE_BLOB_BYTES)
        assertRejected(corrupt {
            SokFormatLayout.writeU32(this, valueOffsetsBase + n * 4, valueBlobBytes + 1)
        }, "valueOffsets")
    }

    @Test
    fun zeroValueCountIsRejected() {
        val n = SokFormatLayout.readU32(valid, SokFormatLayout.OFFSET_ENTRY_COUNT).toInt()
        val valueCountsBase = SokFormatLayout.HEADER_BYTES + 8 * (n + 1)
        assertRejected(corrupt { this[valueCountsBase] = 0 }, "valueCounts")
    }

    @Test
    fun candidateCountExceedingItsRegionIsRejected() {
        // Entry 0's region shrinks to a single byte, too small to hold even
        // the u16 header its valueCount of 1 candidate requires.
        assertRejected(
            corrupt { SokFormatLayout.writeU32(this, valueOffsetsBase + 4, 1) },
            "valueCounts",
        )
    }

    @Test
    fun candidateLengthOvershootingItsRegionIsRejected() {
        // Entry 0 declares a 100-byte payload inside its 3-byte region.
        assertRejected(
            corrupt { SokFormatLayout.writeU16(this, valueBlobBase, 100) },
            "valueBlob",
        )
    }

    @Test
    fun candidateLengthUndershootingItsRegionIsRejected() {
        // Entry 0 declares a 0-byte payload, leaving its region one byte
        // short of the next offset (the encoder rejects empty values).
        assertRejected(
            corrupt { SokFormatLayout.writeU16(this, valueBlobBase, 0) },
            "valueBlob",
        )
    }

    @Test
    fun candidateHeaderInTheLastByteIsRejected() {
        // Regression: valueOffsets [0,3,11,12] is strictly increasing and
        // terminates at the blob size, so it satisfied every pre-existing
        // check and decode() returned a usable dictionary — yet entry 2's
        // cursor sits at bytes.size - 1, so its first access read the u16
        // header past the end of the buffer (ArrayIndexOutOfBoundsException
        // from the production matchAppend path). The structural walk now
        // rejects the file at decode time; the same edit over-stretches
        // entry 1's region, so that is the entry reported.
        assertTrue(valueBlobBase + 11 == valid.size - 1, "entry 2 must start at the last byte")
        assertRejected(
            corrupt { SokFormatLayout.writeU32(this, valueOffsetsBase + 2 * 4, 11) },
            "valueBlob",
        )
    }

    @Test
    fun malformedKeyIsRejected() {
        // A continuation byte where key 0's only byte, 'a', was.
        assertRejected(corrupt { this[keyBlobBase] = 0x80.toByte() }, "key 0")
        // Key 1, "ab", turned into an encoded surrogate prefix and 'b'.
        assertRejected(corrupt { this[keyBlobBase + 1] = 0xED.toByte() }, "key 1")
    }

    @Test
    fun sequenceSplitAcrossTwoKeysIsRejected() {
        // C3 A9 is "é" as a blob, but it straddles keys 0 and 1: key 0 ends in
        // a truncated lead byte and key 1 starts with a stray continuation.
        val bytes = corrupt {
            this[keyBlobBase] = 0xC3.toByte()
            this[keyBlobBase + 1] = 0xA9.toByte()
        }
        assertTrue(Utf8.isWellFormed(bytes, keyBlobBase, 6), "the blob as a whole is well-formed")
        assertRejected(bytes, "key 0")
    }

    @Test
    fun keysOutOfCodePointOrderAreRejected() {
        // Key 2, "abc", becomes "aaa", which sorts before key 1, "ab".
        assertRejected(
            corrupt { "aaa".encodeToByteArray().copyInto(this, keyBlobBase + 3) },
            "key 2 is not above key 1",
        )
    }

    @Test
    fun aRepeatedKeyIsRejected() {
        // keyOffsets [0, 1, 2, 6] re-cut "aababc" as "a", "a" and "babc", and
        // maxKeyLength 4 fits "babc", so only the repetition is wrong.
        assertRejected(corrupt {
            SokFormatLayout.writeU32(this, SokFormatLayout.HEADER_BYTES + 8, 2)
            SokFormatLayout.writeU32(this, SokFormatLayout.OFFSET_MAX_KEY_LENGTH, 4)
        }, "key 1 is not above key 0")
    }

    @Test
    fun keysOrderedByCodePointNotByUtf16AreAccepted() {
        // U+FF61 sorts before U+20000 by code point, but after it by UTF-16
        // code unit (0xFF61 against the high surrogate 0xD840): the decoder
        // must follow the code points, as the encoder does.
        val dictionary = SokDictionaryFormat.decode(
            SokDictionaryEncoder.encode(listOf("\uFF61" to listOf("a"), "𠀀" to listOf("b"))),
        )
        assertEquals(listOf("a"), dictionary.matchExact("\uFF61")?.candidates)
        assertEquals(listOf("b"), dictionary.matchExact("𠀀")?.candidates)
    }

    @Test
    fun wellFormedMultiByteKeysAreAccepted() {
        // U+FFFD included: a well-formed replacement character must not be
        // mistaken for the malformed-sequence result.
        val keys = listOf("é", "中", "\uFFFD", "𠀀").sortedWith { a, b -> Utf.compareByCodePoint(a, b) }
        val dictionary = SokDictionaryFormat.decode(SokDictionaryEncoder.encode(keys.map { it to listOf("<$it>") }))
        for (key in keys) {
            assertEquals(listOf("<$key>"), dictionary.matchExact(key)?.candidates, "key $key")
            assertEquals("<$key>", dictionary.matchPrefix(key.toCharArray(), 0, key.length)?.value, "prefix $key")
        }
    }

    @Test
    fun offsetsReadAsIntEqualTheUnsignedValue() {
        val buffer = ByteArray(4)
        for (value in listOf(0L, 1L, 0x7FL, 0x80L, 0xFFL, 0x100L, 0xFFFFL, 0x10000L, 0x1234567L, 0x7FFFFFFFL)) {
            SokFormatLayout.writeU32(buffer, 0, value)
            assertEquals(SokFormatLayout.readU32(buffer, 0).toInt(), SokFormatLayout.readOffset(buffer, 0), "$value")
            assertEquals(value.toInt(), SokFormatLayout.readOffset(buffer, 0), "$value")
        }
    }
}
