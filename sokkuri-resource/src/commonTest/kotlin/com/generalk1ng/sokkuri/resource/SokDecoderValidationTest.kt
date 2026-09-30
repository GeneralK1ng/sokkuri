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
}
