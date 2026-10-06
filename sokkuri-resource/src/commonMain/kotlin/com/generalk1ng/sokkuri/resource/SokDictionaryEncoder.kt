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

import com.generalk1ng.sokkuri.SokkuriException
import com.generalk1ng.sokkuri.SokkuriInternalApi
import com.generalk1ng.sokkuri.engine.Utf

/**
 * Encodes lexicon entries into the `.sok` container ([SokFormatLayout] is
 * the normative definition of the byte layout).
 *
 * Encoder-side validation: keys and values must be non-empty, keys
 * unique (the error message carries the offending key), at most
 * [SokFormatLayout.MAX_CANDIDATES_PER_ENTRY] candidates per entry, and each
 * candidate at most [SokFormatLayout.MAX_CANDIDATE_BYTES] UTF-8 bytes. Input
 * may be unordered — entries are sorted by code-point order (invariant I2)
 * before writing.
 *
 * Deterministic by construction: same input entries always produce
 * byte-identical output (no timestamps, no hash-order dependence), which the
 * dictgen `--check` mode relies on.
 *
 * Cross-module internal API: `tools:dictgen` calls this to compile the
 * packaged dictionaries, so it lives behind [SokkuriInternalApi] rather
 * than Kotlin `internal` (which stops at module boundaries).
 */
@SokkuriInternalApi
public object SokDictionaryEncoder {

    /**
     * Encodes [entries] into `.sok` bytes. Throws
     * [SokkuriException.InvalidFormat] on the first validation failure.
     */
    public fun encode(entries: List<Pair<String, List<String>>>): ByteArray {
        val sorted = entries.sortedWith { a, b -> Utf.compareByCodePoint(a.first, b.first) }

        val encodedKeys = ArrayList<ByteArray>(sorted.size)
        val encodedCandidates = ArrayList<List<ByteArray>>(sorted.size)
        var keyBlobBytes = 0L
        var valueBlobBytes = 0L
        var maxKeyLength = 0

        var previousKey: String? = null
        for ((key, candidates) in sorted) {
            if (key.isEmpty()) {
                throw SokkuriException.InvalidFormat("sok: dictionary key must not be empty")
            }
            if (candidates.isEmpty()) {
                throw SokkuriException.InvalidFormat("sok: entry '$key' has no values")
            }
            if (candidates.size > SokFormatLayout.MAX_CANDIDATES_PER_ENTRY) {
                throw SokkuriException.InvalidFormat(
                    "sok: entry '$key' has ${candidates.size} values, max ${SokFormatLayout.MAX_CANDIDATES_PER_ENTRY}",
                )
            }
            if (key == previousKey) {
                throw SokkuriException.InvalidFormat("sok: duplicated dictionary key: $key")
            }
            previousKey = key

            val keyBytes = key.encodeToByteArray()
            val candidateBytes = ArrayList<ByteArray>(candidates.size)
            for (candidate in candidates) {
                if (candidate.isEmpty()) {
                    throw SokkuriException.InvalidFormat("sok: entry '$key' has an empty value")
                }
                val bytes = candidate.encodeToByteArray()
                if (bytes.size > SokFormatLayout.MAX_CANDIDATE_BYTES) {
                    throw SokkuriException.InvalidFormat(
                        "sok: value for '$key' is ${bytes.size} bytes, " +
                            "max ${SokFormatLayout.MAX_CANDIDATE_BYTES}",
                    )
                }
                candidateBytes.add(bytes)
            }

            encodedKeys.add(keyBytes)
            encodedCandidates.add(candidateBytes)
            keyBlobBytes += keyBytes.size
            valueBlobBytes += candidateBytes.sumOf { it.size.toLong() + SokFormatLayout.CANDIDATE_HEADER_BYTES }
            if (key.length > maxKeyLength) maxKeyLength = key.length
        }

        val entryCount = sorted.size
        val totalBytes = SokFormatLayout.HEADER_BYTES.toLong() +
            4L * (entryCount + 1) + 4L * (entryCount + 1) + entryCount +
            keyBlobBytes + valueBlobBytes
        if (totalBytes > Int.MAX_VALUE) {
            throw SokkuriException.InvalidFormat("sok: encoded dictionary exceeds ${Int.MAX_VALUE} bytes")
        }
        val buffer = ByteArray(totalBytes.toInt())

        // Header.
        for (i in 0..3) buffer[i] = SokFormatLayout.MAGIC[i].code.toByte()
        SokFormatLayout.writeU32(buffer, SokFormatLayout.OFFSET_VERSION, SokFormatLayout.VERSION.toLong())
        SokFormatLayout.writeU32(buffer, SokFormatLayout.OFFSET_FLAGS, 0)
        SokFormatLayout.writeU32(buffer, SokFormatLayout.OFFSET_ENTRY_COUNT, entryCount.toLong())
        SokFormatLayout.writeU32(buffer, SokFormatLayout.OFFSET_MAX_KEY_LENGTH, maxKeyLength.toLong())
        SokFormatLayout.writeU32(buffer, SokFormatLayout.OFFSET_KEY_BLOB_BYTES, keyBlobBytes)
        SokFormatLayout.writeU32(buffer, SokFormatLayout.OFFSET_VALUE_BLOB_BYTES, valueBlobBytes)

        val keyOffsetsBase = SokFormatLayout.HEADER_BYTES
        val valueOffsetsBase = keyOffsetsBase + 4 * (entryCount + 1)
        val valueCountsBase = valueOffsetsBase + 4 * (entryCount + 1)
        val keyBlobBase = valueCountsBase + entryCount
        val valueBlobBase = keyBlobBase + keyBlobBytes.toInt()

        // Offset tables and valueCounts.
        var keyCursor = 0
        var valueCursor = 0
        for (index in 0 until entryCount) {
            SokFormatLayout.writeU32(buffer, keyOffsetsBase + index * 4, keyCursor.toLong())
            keyCursor += encodedKeys[index].size
            SokFormatLayout.writeU32(buffer, valueOffsetsBase + index * 4, valueCursor.toLong())
            valueCursor += encodedCandidates[index].sumOf { it.size + SokFormatLayout.CANDIDATE_HEADER_BYTES }
            buffer[valueCountsBase + index] = encodedCandidates[index].size.toByte()
        }
        SokFormatLayout.writeU32(buffer, keyOffsetsBase + entryCount * 4, keyBlobBytes)
        SokFormatLayout.writeU32(buffer, valueOffsetsBase + entryCount * 4, valueBlobBytes)

        // Blobs.
        var keyWrite = keyBlobBase
        for (keyBytes in encodedKeys) {
            keyBytes.copyInto(buffer, keyWrite)
            keyWrite += keyBytes.size
        }
        var valueWrite = valueBlobBase
        for (candidates in encodedCandidates) {
            for (candidate in candidates) {
                SokFormatLayout.writeU16(buffer, valueWrite, candidate.size)
                candidate.copyInto(buffer, valueWrite + SokFormatLayout.CANDIDATE_HEADER_BYTES)
                valueWrite += SokFormatLayout.CANDIDATE_HEADER_BYTES + candidate.size
            }
        }
        return buffer
    }
}
