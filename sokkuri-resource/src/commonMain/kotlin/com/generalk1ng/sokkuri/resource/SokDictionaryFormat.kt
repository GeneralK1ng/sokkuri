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
 * replacement for OpenCC's non-portable `.ocd`/`.ocd2` marisa binaries.
 * [SokFormatLayout] defines the byte layout and [decode] applies the strict
 * validation. Deliberate divergences from the `text` format:
 *
 * - zero-copy: the file's bytes are retained and keys/candidates are decoded
 *   on demand, so dictionary memory stays at raw-file size plus the shared
 *   retrieval index;
 * - retrieval is delegated to [SortedTableRetrieval], the same sorted-table
 *   algorithm [com.generalk1ng.sokkuri.engine.SortedListDictionary] uses, so
 *   the two backends cannot drift.
 */
@SokkuriInternalApi
public object SokDictionaryFormat : DictionaryFormat {

    override val type: String = "sok"

    override fun decode(bytes: ByteArray): Dictionary = SokDictionary.decode(bytes)
}

/**
 * An immutable, thread-safe [Dictionary] view over an `.sok` byte buffer.
 * Constructed only through [decode], which enforces the strict validation.
 */
@SokkuriInternalApi
public class SokDictionary internal constructor(
    private val bytes: ByteArray,
    /** Number of entries, as stored in the header. */
    public val entryCount: Int,
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
     * offset table and the key's UTF-8 bytes in place ([Utf8]), so the
     * binary-search and narrowing hot paths materialize nothing. Keys were
     * checked well-formed when the file was decoded, so a probe decodes the
     * code point it needs without validating it again. No decoded-key
     * caching anywhere (zero-copy invariant): every access recomputes from
     * the immutable `bytes`, keeping the dictionary thread-safe (I3) at
     * raw-file memory.
     */
    private inner class BlobTable : SortedKeyTable {

        override val entryCount: Int get() = this@SokDictionary.entryCount

        override val maxKeyLength: Int get() = this@SokDictionary.maxKeyLength

        override fun keyFirstCodePointAt(index: Int): Int = Utf8.wellFormedCodePointAt(bytes, keyStart(index))

        override fun keyCodePointAt(index: Int, offset: Int): Int {
            val position = keyStart(index) + offset
            return if (position >= keyEnd(index)) -1 else Utf8.wellFormedCodePointAt(bytes, position)
        }

        override fun encodedWidth(codePoint: Int): Int = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }

        override fun compareKeyAt(index: Int, text: CharArray, start: Int, end: Int): Int {
            val keyOffset = keyStart(index)
            return Utf8.compareRegionToWindow(bytes, keyOffset, keyEnd(index) - keyOffset, text, start, end)
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
        valueBlobBase + SokFormatLayout.readOffset(bytes, valueOffsetsBase + index * 4)

    /** Byte range `[start, end)` of the key at [index] inside [bytes]. */
    private fun keyStart(index: Int): Int =
        keyBlobBase + SokFormatLayout.readOffset(bytes, keyOffsetsBase + index * 4)

    private fun keyEnd(index: Int): Int =
        keyBlobBase + SokFormatLayout.readOffset(bytes, keyOffsetsBase + (index + 1) * 4)

    override fun matchExact(key: String): DictionaryEntry? =
        retrieval.indexOf(key).let { if (it < 0) null else entryAt(it) }

    override fun matchPrefix(text: CharArray, start: Int, end: Int): PrefixMatch? =
        retrieval.matchPrefix(text, start, end)

    override fun matchAppend(text: CharArray, start: Int, end: Int, out: StringBuilder): Int =
        retrieval.matchAppend(text, start, end, out)

    override fun matchLength(text: CharArray, start: Int, end: Int): Int =
        retrieval.matchLength(text, start, end)

    override fun mayStartKey(codePoint: Int): Boolean = retrieval.mayStartKey(codePoint)

    override fun keyStarts(): CodePointSet = retrieval.keyStarts()

    /**
     * The stored bytes of key [index], not decoded. With [entryCount] and
     * [rawCandidates], this is what the conformance tests against the
     * independent Rust reader compare (docs/design/sok-rs.md §6): bytes, so a
     * malformed value is compared as what it is rather than as each reader's
     * replacement text. A fresh copy; never called at run time.
     *
     * @throws IndexOutOfBoundsException when [index] is not below [entryCount].
     */
    public fun rawKey(index: Int): ByteArray {
        checkEntryIndex(index)
        return bytes.copyOfRange(keyStart(index), keyEnd(index))
    }

    /**
     * The stored bytes of each candidate of entry [index], in order, without
     * their length prefixes, as fresh copies; the companion of [rawKey].
     *
     * @throws IndexOutOfBoundsException when [index] is not below [entryCount].
     */
    public fun rawCandidates(index: Int): List<ByteArray> {
        checkEntryIndex(index)
        val count = bytes[valueCountsBase + index].toInt() and 0xFF
        val candidates = ArrayList<ByteArray>(count)
        var cursor = valueCursor(index)
        repeat(count) {
            val start = cursor + SokFormatLayout.CANDIDATE_HEADER_BYTES
            val end = start + SokFormatLayout.readU16(bytes, cursor)
            candidates.add(bytes.copyOfRange(start, end))
            cursor = end
        }
        return candidates
    }

    private fun checkEntryIndex(index: Int) {
        if (index !in 0..<entryCount) throw IndexOutOfBoundsException("entry $index of $entryCount")
    }

    private fun keyAt(index: Int): String = bytes.decodeToString(keyStart(index), keyEnd(index))

    private fun defaultValueAt(index: Int): String {
        val cursor = valueCursor(index)
        val length = SokFormatLayout.readU16(bytes, cursor)
        return bytes.decodeToString(cursor + SokFormatLayout.CANDIDATE_HEADER_BYTES, cursor + SokFormatLayout.CANDIDATE_HEADER_BYTES + length)
    }

    private fun entryAt(index: Int): DictionaryEntry {
        val count = bytes[valueCountsBase + index].toInt() and 0xFF
        val candidates = ArrayList<String>(count)
        var cursor = valueCursor(index)
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
         * decoder validation: magic, version, flags and the reserved field,
         * entryCount consistency with the file size, offset tables rising
         * strictly from 0 to their blob sizes, well-formed keys in strictly
         * increasing code-point order, maxKeyLength equal to the longest
         * key, `valueCounts ≥ 1`, and each
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
            val reserved = SokFormatLayout.readU32(bytes, SokFormatLayout.OFFSET_RESERVED)
            if (reserved != 0L) {
                throw SokkuriException.InvalidFormat("sok: reserved field must be 0, was $reserved")
            }
            val entryCount = SokFormatLayout.readU32(bytes, SokFormatLayout.OFFSET_ENTRY_COUNT)
            val maxKeyLength = SokFormatLayout.readU32(bytes, SokFormatLayout.OFFSET_MAX_KEY_LENGTH)
            val keyBlobBytes = SokFormatLayout.readU32(bytes, SokFormatLayout.OFFSET_KEY_BLOB_BYTES)
            val valueBlobBytes = SokFormatLayout.readU32(bytes, SokFormatLayout.OFFSET_VALUE_BLOB_BYTES)

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
            // at their blob size (strictness also excludes empty keys). From
            // 0, so that no blob starts with bytes no entry refers to.
            val firstKeyOffset = SokFormatLayout.readU32(bytes, keyOffsetsBase)
            if (firstKeyOffset != 0L) {
                throw SokkuriException.InvalidFormat("sok: keyOffsets[0] must be 0, was $firstKeyOffset")
            }
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
            // Each key must be well-formed UTF-8 on its own: retrieval probes
            // keys without validating them (Utf8.wellFormedCodePointAt), so a
            // malformed key is rejected here, once, rather than tolerated on
            // every probe. Per key, not per blob: a sequence split across two
            // keys is valid as a blob but leaves both keys malformed.
            // Each key must also sort strictly after the one before it
            // (invariant I2): retrieval's binary searches rely on the order,
            // and strictness rules out a repeated key. For well-formed UTF-8
            // the unsigned byte order is the code-point order, and the key
            // before this one is the region that ends where it starts.
            var longestKey = 0
            for (i in 0 until n) {
                val start = SokFormatLayout.readU32(bytes, keyOffsetsBase + i * 4).toInt()
                val end = SokFormatLayout.readU32(bytes, keyOffsetsBase + (i + 1) * 4).toInt()
                if (!Utf8.isWellFormed(bytes, keyBlobBase + start, end - start)) {
                    throw SokkuriException.InvalidFormat("sok: key $i is not well-formed UTF-8")
                }
                if (i > 0) {
                    val previousStart = SokFormatLayout.readU32(bytes, keyOffsetsBase + (i - 1) * 4).toInt()
                    if (compareBytes(bytes, keyBlobBase + previousStart, keyBlobBase + start, keyBlobBase + end) >= 0) {
                        throw SokkuriException.InvalidFormat(
                            "sok: key $i is not above key ${i - 1} in code-point order",
                        )
                    }
                }
                // A key never has more UTF-16 code units than UTF-8 bytes, so
                // only one longer in bytes than the longest so far can be
                // longer in code units; most keys skip the count.
                if (end - start > longestKey) {
                    longestKey = maxOf(longestKey, utf16Length(bytes, keyBlobBase + start, keyBlobBase + end))
                }
            }
            // maxKeyLength is the longest key in UTF-16 code units (invariant
            // I1): retrieval cuts its window to it, so a smaller value would
            // silently lose the matches of longer keys.
            if (maxKeyLength != longestKey.toLong()) {
                throw SokkuriException.InvalidFormat(
                    "sok: maxKeyLength is $maxKeyLength, but the longest key has $longestKey UTF-16 code units",
                )
            }
            val firstValueOffset = SokFormatLayout.readU32(bytes, valueOffsetsBase)
            if (firstValueOffset != 0L) {
                throw SokkuriException.InvalidFormat("sok: valueOffsets[0] must be 0, was $firstValueOffset")
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

        /**
         * Compares the adjacent regions `[firstStart, secondStart)` and
         * `[secondStart, secondEnd)` of [bytes] as unsigned bytes: negative
         * when the first sorts before the second, zero when they are equal.
         */
        private fun compareBytes(bytes: ByteArray, firstStart: Int, secondStart: Int, secondEnd: Int): Int {
            val firstLength = secondStart - firstStart
            val secondLength = secondEnd - secondStart
            for (k in 0 until minOf(firstLength, secondLength)) {
                val difference = (bytes[firstStart + k].toInt() and 0xFF) - (bytes[secondStart + k].toInt() and 0xFF)
                if (difference != 0) return difference
            }
            return firstLength - secondLength
        }

        /**
         * UTF-16 code units of the well-formed UTF-8 in `[start, end)`: one
         * for every sequence, two for a four-byte one. Counts lead bytes
         * rather than decoding, which only well-formedness makes correct.
         */
        private fun utf16Length(bytes: ByteArray, start: Int, end: Int): Int {
            var units = 0
            for (k in start until end) {
                val byte = bytes[k].toInt() and 0xFF
                if (byte < 0x80 || byte >= 0xC0) units += if (byte >= 0xF0) 2 else 1
            }
            return units
        }
    }
}
