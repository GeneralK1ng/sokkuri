@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)

package com.generalk1ng.sokkuri.resource

import com.generalk1ng.sokkuri.SokkuriException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Strict-decoder validation tests (m1-dictgen-sok §2.5 / §3.2): every
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
     * `[u16 1]"A" [u16 2]"AB" [u16 3]"ABC"` and valueOffsets is [0, 3, 6, 9].
     */
    private val valueBlobBase: Int = valueOffsetsBase + 4 * (entryCount + 1) + entryCount +
        SokFormatLayout.readU32(valid, SokFormatLayout.OFFSET_KEY_BLOB_BYTES).toInt()

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
    fun entryCountInconsistentWithFileSizeIsRejected() {
        assertRejected(corrupt {
            SokFormatLayout.writeU32(this, SokFormatLayout.OFFSET_ENTRY_COUNT, 4)
        }, "entryCount")
    }

    @Test
    fun maxKeyLengthBeyondTheKeyBlobIsRejected() {
        assertRejected(corrupt {
            SokFormatLayout.writeU32(this, SokFormatLayout.OFFSET_MAX_KEY_LENGTH, 1000)
        }, "maxKeyLength")
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
        // Regression: valueOffsets [0,3,8,9] is strictly increasing and
        // terminates at the blob size, so it satisfied every pre-existing
        // check and decode() returned a usable dictionary — yet entry 2's
        // cursor sits at bytes.size - 1, so its first access read the u16
        // header past the end of the buffer (ArrayIndexOutOfBoundsException
        // from the production matchAppend path). The structural walk now
        // rejects the file at decode time; the same edit over-stretches
        // entry 1's region, so that is the entry reported.
        assertRejected(
            corrupt { SokFormatLayout.writeU32(this, valueOffsetsBase + 2 * 4, 8) },
            "valueBlob",
        )
    }
}
