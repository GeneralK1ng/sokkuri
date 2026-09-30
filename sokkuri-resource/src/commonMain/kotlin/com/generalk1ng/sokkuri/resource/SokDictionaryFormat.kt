package com.generalk1ng.sokkuri.resource

import com.generalk1ng.sokkuri.SokkuriException
import com.generalk1ng.sokkuri.SokkuriInternalApi
import com.generalk1ng.sokkuri.engine.Dictionary
import com.generalk1ng.sokkuri.engine.DictionaryEntry
import com.generalk1ng.sokkuri.engine.PrefixMatch
import com.generalk1ng.sokkuri.engine.SortedTableRetrieval

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

    private val retrieval = SortedTableRetrieval(
        entryCount = entryCount,
        maxKeyLength = maxKeyLength,
        keyAt = { keyAt(it) },
        defaultValueAt = { defaultValueAt(it) },
    )

    override fun matchExact(key: String): DictionaryEntry? =
        retrieval.indexOf(key).let { if (it < 0) null else entryAt(it) }

    override fun matchPrefix(text: CharArray, start: Int, end: Int): PrefixMatch? =
        retrieval.matchPrefix(text, start, end)

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
        val cursor = valueBlobBase +
            SokFormatLayout.readU32(bytes, valueOffsetsBase + index * 4).toInt()
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
         * terminating at their blob sizes, and `valueCounts ≥ 1`. Every
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
