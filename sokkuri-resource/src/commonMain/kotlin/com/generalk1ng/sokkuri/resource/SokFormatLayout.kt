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

package com.generalk1ng.sokkuri.resource

/**
 * Byte-level layout of the `.sok` dictionary container — the normative
 * definition of the format, and the single source of truth for both the
 * encoder ([SokDictionaryEncoder]) and the decoder ([SokDictionary]), so
 * the two sides can never disagree on it.
 *
 * All multi-byte fields are unsigned and little-endian, making the file
 * byte-identical across platforms (the portability lesson of OpenCC's
 * word-size-dependent `.ocd` formats, which this format replaces).
 *
 * ```
 * Offset  Size      Field
 * 0       4         magic "SOK1"
 * 4       4         formatVersion = 1
 * 8       4         flags = 0 (reserved; decoders must reject non-zero)
 * 12      4         entryCount
 * 16      4         maxKeyLength (UTF-16 code units, invariant I1)
 * 20      4         keyBlobBytes
 * 24      4         valueBlobBytes
 * 28      4         reserved = 0
 * 32      4×(n+1)   keyOffsets, strictly increasing, last = keyBlobBytes
 * …       4×(n+1)   valueOffsets, strictly increasing, last = valueBlobBytes
 * …       n         valueCounts (u8), candidates per entry, ≥ 1
 * …       *         keyBlob: UTF-8 keys in code-point order (invariant I2)
 * …       *         valueBlob: per candidate u16 byteLen + UTF-8 bytes; the
 *                   candidates of entry i tile [valueOffsets[i],
 *                   valueOffsets[i+1]) exactly (decoder-enforced)
 * ```
 */
internal object SokFormatLayout {

    internal const val MAGIC: String = "SOK1"

    internal const val VERSION: Int = 1

    /** Header size in bytes; the offsets region starts right after it. */
    internal const val HEADER_BYTES: Int = 32

    internal const val OFFSET_VERSION: Int = 4
    internal const val OFFSET_FLAGS: Int = 8
    internal const val OFFSET_ENTRY_COUNT: Int = 12
    internal const val OFFSET_MAX_KEY_LENGTH: Int = 16
    internal const val OFFSET_KEY_BLOB_BYTES: Int = 20
    internal const val OFFSET_VALUE_BLOB_BYTES: Int = 24

    /** u8 cap on candidates per entry (the valueCounts column). */
    internal const val MAX_CANDIDATES_PER_ENTRY: Int = 0xFF

    /** u16 byte-length prefix on every candidate in the value blob. */
    internal const val CANDIDATE_HEADER_BYTES: Int = 2
    internal const val MAX_CANDIDATE_BYTES: Int = 0xFFFF

    /** Reads an unsigned little-endian u16. */
    internal fun readU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    /** Reads an unsigned little-endian u32 as a [Long] (never negative). */
    internal fun readU32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    /** Writes [value] (treated as unsigned) as a little-endian u16. */
    internal fun writeU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    /** Writes [value] (treated as unsigned) as a little-endian u32. */
    internal fun writeU32(buffer: ByteArray, offset: Int, value: Long) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
