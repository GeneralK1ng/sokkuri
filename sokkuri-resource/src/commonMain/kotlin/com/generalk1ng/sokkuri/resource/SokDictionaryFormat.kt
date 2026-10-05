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
import com.generalk1ng.sokkuri.engine.*

/**
 * Decoder for the `.sok` binary dictionary format, the production
 * replacement for OpenCC's non-portable `.ocd`/`.ocd2` marisa binaries
 * (docs/milestones/m1-dictgen-sok.md §3 defines the byte layout; §3.2 the
 * strict validation). Deliberate divergences from the `text` format:
 *
 * - zero-copy: the file's bytes are retained and keys/candidates are decoded
 *   on demand, so dictionary memory stays at raw-file size plus the shared
 *   retrieval index;
 * - retrieval is delegated to [SortedTableRetrieval], the same sorted-table
 *   algorithm [com.generalk1ng.sokkuri.engine.SortedListDictionary] uses, so
 *   the two backends cannot drift (m1-dictgen-sok §3.3).
 */
@SokkuriInternalApi
public object SokDictionaryFormat : DictionaryFormat {

    override val type: String = "sok"

    override fun decode(bytes: ByteArray): Dictionary = SokDictionary.decode(bytes)
}

/**
 * An immutable, thread-safe [Dictionary] view over an `.sok` byte buffer.
 * Constructed only through [decode], which enforces the §3.2 validation.
 */
@SokkuriInternalApi
public class SokDictionary internal constructor(
    private val bytes: ByteArray,
    private val entryCount: Int,
    override val maxKeyLength: Int,
    private val keyOffsetsBase: Int,
    private val valueOffsetsBase: Int,
    private val valueCountsBase: Int,
    private val keyBlobBase: Int,
    private val valueBlobBase: Int,
) : Dictionary {

    private val retrieval = SortedTableRetrieval.over(BlobTable())

    /**
     * [SortedKeyTable] over this dictionary's byte blobs: probes read the
     * offset table and compare raw UTF-8 regions against input windows
     * ([Utf8]), so the binary-search and group-scan hot paths decode
     * nothing — m3-string-view.md §3.1's probe elimination. No decoded-key
     * caching anywhere (zero-copy invariant): every access recomputes from
     * the immutable `bytes`, keeping the dictionary thread-safe (I3) at
     * raw-file memory.
     */
    private inner class BlobTable : SortedKeyTable {

        override val entryCount: Int get() = this@SokDictionary.entryCount

        override val maxKeyLength: Int get() = this@SokDictionary.maxKeyLength

        override fun keyFirstCodePointAt(index: Int): Int {
            val start = keyStart(index)
            return Utf8.firstCodePointAt(bytes, start, keyEnd(index) - start)
        }

        override fun keyUtf16LengthAt(index: Int): Int {
            val start = keyStart(index)
            return Utf8.utf16LengthOf(bytes, start, keyEnd(index) - start)
        }

        override fun compareKeyAt(index: Int, text: CharArray, start: Int, end: Int): Int {
            val keyOffset = keyStart(index)
            return Utf8.compareRegionToWindow(bytes, keyOffset, keyEnd(index) - keyOffset, text, start, end)
        }

        override fun keyPrefixesWindowAt(index: Int, text: CharArray, start: Int, end: Int): Boolean {
            val keyOffset = keyStart(index)
            return Utf8.startsWithRegionAt(bytes, keyOffset, keyEnd(index) - keyOffset, text, start, end)
        }

        override fun defaultValueAt(index: Int): String = this@SokDictionary.defaultValueAt(index)

        override fun appendDefaultValueAt(index: Int, out: StringBuilder) {
            val cursor = valueCursor(index)
            val length = SokFormatLayout.readU16(bytes, cursor)
            Utf8.decodeAppend(
                bytes,
                cursor + SokFormatLayout.CANDIDATE_HEADER_BYTES,
                length,
                out,
            )
        }
    }

    /** Cursor of the first candidate of the entry at [index]: a u16 byte length at [cursor], UTF-8 payload after it. */
    private fun valueCursor(index: Int): Int =
        valueBlobBase + SokFormatLayout.readU32(bytes, valueOffsetsBase + index * 4).toInt()

    /** Byte range `[start, end)` of the key at [index] inside [bytes]. */
    private fun keyStart(index: Int): Int =
        keyBlobBase + SokFormatLayout.readU32(bytes, keyOffsetsBase + index * 4).toInt()

    private fun keyEnd(index: Int): Int =
        keyBlobBase + SokFormatLayout.readU32(bytes, keyOffsetsBase + (index + 1) * 4).toInt()

    override fun matchExact(key: String): DictionaryEntry? =
        retrieval.indexOf(key).let { if (it < 0) null else entryAt(it) }

    override fun matchPrefix(text: CharArray, start: Int, end: Int): PrefixMatch? =
        retrieval.matchPrefix(text, start, end)

    override fun matchAppend(text: CharArray, start: Int, end: Int, out: StringBuilder): Int =
        retrieval.matchAppend(text, start, end, out)

    override fun mayStartKey(codePoint: Int): Boolean = retrieval.mayStartKey(codePoint)

    private fun keyAt(index: Int): String {
        val start = keyBlobBase +
            SokFormatLayout.readU32(bytes, keyOffsetsBase + index * 4).toInt()
        val end = keyBlobBase +
            SokFormatLayout.readU32(bytes, keyOffsetsBase + (index + 1) * 4).toInt()
        // §3.2 known tradeoff: UTF-8 validity is the encoder's invariant and
        // deliberately not re-validated per access.
        return bytes.decodeToString(start, end)
    }

    private fun defaultValueAt(index: Int): String {
        val cursor = valueCursor(index)
        val length = SokFormatLayout.readU16(bytes, cursor)
        return bytes.decodeToString(cursor + SokFormatLayout.CANDIDATE_HEADER_BYTES, cursor + SokFormatLayout.CANDIDATE_HEADER_BYTES + length)
    }

    private fun entryAt(index: Int): DictionaryEntry {
        val count = bytes[valueCountsBase + index].toInt() and 0xFF
        val candidates = ArrayList<String>(count)
        var cursor = valueBlobBase +
            SokFormatLayout.readU32(bytes, valueOffsetsBase + index * 4).toInt()
        repeat(count) {
            val length = SokFormatLayout.readU16(bytes, cursor)
            candidates.add(
                bytes.decodeToString(
                    cursor + SokFormatLayout.CANDIDATE_HEADER_BYTES,
                    cursor + SokFormatLayout.CANDIDATE_HEADER_BYTES + length,
                ),
            )
            cursor += SokFormatLayout.CANDIDATE_HEADER_BYTES + length
        }
        return SokEntry(keyAt(index), candidates)
    }

    private class SokEntry(
        override val key: String,
        override val candidates: List<String>,
    ) : DictionaryEntry

    internal companion object {

        /**
         * Decodes [bytes] into a [SokDictionary], applying the strict
         * decoder validation of m1-dictgen-sok §3.2: magic/version/flags,
         * entryCount consistency with the file size, monotone offset tables
         * terminating at their blob sizes, `valueCounts ≥ 1`, and each
         * entry's candidates tiling its valueOffsets region exactly. Every
         * failure is an [SokkuriException.InvalidFormat] naming the field.
         */
        internal fun decode(bytes: ByteArray): SokDictionary {
            if (bytes.size < SokFormatLayout.HEADER_BYTES) {
                throw SokkuriException.InvalidFormat(
                    "sok: file is ${bytes.size} bytes, shorter than the ${SokFormatLayout.HEADER_BYTES}-byte header",
                )
            }
            for (i in 0..3) {
                if (bytes[i] != SokFormatLayout.MAGIC[i].code.toByte()) {
                    throw SokkuriException.InvalidFormat("sok: bad magic, expected '${SokFormatLayout.MAGIC}'")
                }
            }
            val version = SokFormatLayout.readU32(bytes, SokFormatLayout.OFFSET_VERSION)
            if (version != SokFormatLayout.VERSION.toLong()) {
                throw SokkuriException.InvalidFormat("sok: unsupported formatVersion $version")
            }
            val flags = SokFormatLayout.readU32(bytes, SokFormatLayout.OFFSET_FLAGS)
            if (flags != 0L) {
                throw SokkuriException.InvalidFormat("sok: flags must be 0, was $flags")
            }
            val entryCount = SokFormatLayout.readU32(bytes, SokFormatLayout.OFFSET_ENTRY_COUNT)
            val maxKeyLength = SokFormatLayout.readU32(bytes, SokFormatLayout.OFFSET_MAX_KEY_LENGTH)
            val keyBlobBytes = SokFormatLayout.readU32(bytes, SokFormatLayout.OFFSET_KEY_BLOB_BYTES)
            val valueBlobBytes = SokFormatLayout.readU32(bytes, SokFormatLayout.OFFSET_VALUE_BLOB_BYTES)

            // Every key holds at least one UTF-16 code unit per byte, so a
            // maxKeyLength beyond the blob can only come from a corrupt file.
            if (maxKeyLength > keyBlobBytes) {
                throw SokkuriException.InvalidFormat(
                    "sok: maxKeyLength $maxKeyLength exceeds keyBlobBytes $keyBlobBytes",
                )
            }

            // Region layout derived from entryCount; the declared total must
            // match the actual file size exactly (Long math: no overflow).
            val expectedSize = SokFormatLayout.HEADER_BYTES.toLong() +
                4L * (entryCount + 1) + 4L * (entryCount + 1) + entryCount +
                keyBlobBytes + valueBlobBytes
            if (expectedSize != bytes.size.toLong()) {
                throw SokkuriException.InvalidFormat(
                    "sok: entryCount $entryCount implies a file of $expectedSize bytes, " +
                        "but the file is ${bytes.size} bytes",
                )
            }

            val n = entryCount.toInt()
            val keyOffsetsBase = SokFormatLayout.HEADER_BYTES
            val valueOffsetsBase = keyOffsetsBase + 4 * (n + 1)
            val valueCountsBase = valueOffsetsBase + 4 * (n + 1)
            val keyBlobBase = valueCountsBase + n
            val valueBlobBase = keyBlobBase + keyBlobBytes.toInt()

            // Offset tables: strictly increasing from 0, terminating exactly
            // at their blob size (strictness also excludes empty keys).
            var previousKeyOffset = -1L
            for (i in 0..n) {
                val offset = SokFormatLayout.readU32(bytes, keyOffsetsBase + i * 4)
                if (offset <= previousKeyOffset) {
                    throw SokkuriException.InvalidFormat(
                        "sok: keyOffsets not strictly increasing at index $i",
                    )
                }
                previousKeyOffset = offset
            }
            if (SokFormatLayout.readU32(bytes, keyOffsetsBase + n * 4) != keyBlobBytes) {
                throw SokkuriException.InvalidFormat(
                    "sok: keyOffsets[$n] must equal keyBlobBytes $keyBlobBytes",
                )
            }
            var previousValueOffset = -1L
            for (i in 0..n) {
                val offset = SokFormatLayout.readU32(bytes, valueOffsetsBase + i * 4)
                if (offset <= previousValueOffset) {
                    throw SokkuriException.InvalidFormat(
                        "sok: valueOffsets not strictly increasing at index $i",
                    )
                }
                previousValueOffset = offset
            }
            if (SokFormatLayout.readU32(bytes, valueOffsetsBase + n * 4) != valueBlobBytes) {
                throw SokkuriException.InvalidFormat(
                    "sok: valueOffsets[$n] must equal valueBlobBytes $valueBlobBytes",
                )
            }
            for (i in 0 until n) {
                if (bytes[valueCountsBase + i] == 0.toByte()) {
                    throw SokkuriException.InvalidFormat("sok: valueCounts[$i] is 0")
                }
            }

            // Value-blob structure: an entry's candidates must tile its own
            // [valueOffsets[i], valueOffsets[i + 1]) region exactly, since
            // the encoder writes nothing else there. The offset table alone
            // cannot express this, so without the walk below a
            // size-consistent but corrupt file passes every check above and
            // then reads past the buffer on first access (appendDefaultValueAt
            // reads the u16 header, entryAt decodes the payload). Every read
            // stays in bounds: the cursor never passes `end`, and `end` is at
            // most valueBlobBytes, whose tail is the last byte of the file.
            for (i in 0 until n) {
                val start = SokFormatLayout.readU32(bytes, valueOffsetsBase + i * 4).toInt()
                val end = SokFormatLayout.readU32(bytes, valueOffsetsBase + (i + 1) * 4).toInt()
                val count = bytes[valueCountsBase + i].toInt() and 0xFF
                var cursor = start
                repeat(count) {
                    if (cursor + SokFormatLayout.CANDIDATE_HEADER_BYTES > end) {
                        throw SokkuriException.InvalidFormat(
                            "sok: valueCounts[$i] is $count, too many for its " +
                                "${end - start}-byte valueOffsets region",
                        )
                    }
                    cursor += SokFormatLayout.CANDIDATE_HEADER_BYTES +
                        SokFormatLayout.readU16(bytes, valueBlobBase + cursor)
                }
                if (cursor != end) {
                    throw SokkuriException.InvalidFormat(
                        "sok: valueBlob of entry $i ends at $cursor, " +
                            "but valueOffsets[${i + 1}] is $end",
                    )
                }
            }

            return SokDictionary(
                bytes = bytes,
                entryCount = n,
                maxKeyLength = maxKeyLength.toInt(),
                keyOffsetsBase = keyOffsetsBase,
                valueOffsetsBase = valueOffsetsBase,
                valueCountsBase = valueCountsBase,
                keyBlobBase = keyBlobBase,
                valueBlobBase = valueBlobBase,
            )
        }
    }
}
