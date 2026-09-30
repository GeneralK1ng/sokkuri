package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.SokkuriInternalApi

import com.generalk1ng.sokkuri.SokkuriException

/**
 * A dictionary backed by a key-sorted array with binary search — the port of
 * OpenCC's `TextDict`. Used for `inline` config dictionaries and as the
 * reference implementation for validating the production binary format.
 *
 * Entries must have unique keys; they are sorted by code-point order
 * ([Utf.compareByCodePoint]) when unsorted input is given.
 */
@SokkuriInternalApi
public class SortedListDictionary private constructor(
    // Array, not List, to keep a distinct JVM signature from the public
    // pairs-based constructor (type erasure would clash on List).
    private val entries: Array<Entry>,
) : Dictionary {

    public constructor(pairs: List<Pair<String, List<String>>>) : this(
        entries = toEntries(pairs).toTypedArray(),
    )

    private class Entry(
        override val key: String,
        override val candidates: List<String>,
    ) : DictionaryEntry

    override val maxKeyLength: Int = entries.maxOfOrNull { it.key.length } ?: 0

    private val firstCodePoints: Set<Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        entries.mapTo(HashSet()) { Utf.codePointAt(it.key, 0) }
    }

    override fun matchExact(key: String): DictionaryEntry? {
        val index = lowerBound(key)
        return if (index < entries.size && Utf.compareByCodePoint(entries[index].key, key) == 0) {
            entries[index]
        } else {
            null
        }
    }

    override fun matchPrefix(text: CharArray, start: Int, end: Int): PrefixMatch? {
        if (start >= end) return null
        val first = Utf.codePointAt(text, start, end)
        if (first !in firstCodePoints) return null

        val probe = singleCodePointString(first)
        var index = lowerBound(probe)
        var best: PrefixMatch? = null
        val maxEnd = minOf(end, start + maxKeyLength)
        while (index < entries.size) {
            val key = entries[index].key
            // Keys are code-point ordered: once the first code point diverges,
            // no further entry can prefix this text.
            if (Utf.codePointAt(key, 0) != first) break
            if (key.length <= maxEnd - start && Utf.startsWithAt(text, start, maxEnd, key)) {
                if (best == null || key.length > best.length) {
                    best = PrefixMatch(key.length, entries[index].candidates.first())
                }
            }
            index += 1
        }
        return best
    }

    override fun mayStartKey(codePoint: Int): Boolean = codePoint in firstCodePoints

    /** First index whose key is >= [probe] in code-point order. */
    private fun lowerBound(probe: String): Int {
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (Utf.compareByCodePoint(entries[mid].key, probe) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    public companion object {
        /** One-code-point string without JVM-only `Character.toChars`. */
        private fun singleCodePointString(codePoint: Int): String {
            if (codePoint < 0x10000) return Char(codePoint).toString()
            return charArrayOf(
                Char(((codePoint - 0x10000) ushr 10) + 0xD800),
                Char(((codePoint - 0x10000) and 0x3FF) + 0xDC00),
            ).concatToString()
        }

        private fun toEntries(pairs: List<Pair<String, List<String>>>): List<Entry> {
            val sorted = pairs.sortedWith { a, b -> Utf.compareByCodePoint(a.first, b.first) }
            val result = ArrayList<Entry>(sorted.size)
            var previous: String? = null
            for ((key, candidates) in sorted) {
                if (key.isEmpty()) {
                    throw SokkuriException.InvalidFormat("dictionary key must not be empty")
                }
                if (candidates.isEmpty()) {
                    throw SokkuriException.InvalidFormat("dictionary entry '$key' has no values")
                }
                if (key == previous) {
                    throw SokkuriException.InvalidFormat("duplicated dictionary key: $key")
                }
                previous = key
                result.add(Entry(key, candidates))
            }
            return result
        }
    }
}
