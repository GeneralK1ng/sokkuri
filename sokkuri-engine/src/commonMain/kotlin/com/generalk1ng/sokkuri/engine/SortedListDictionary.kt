package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.SokkuriInternalApi

import com.generalk1ng.sokkuri.SokkuriException

/**
 * A dictionary backed by a key-sorted array with binary search — the port of
 * OpenCC's `TextDict`. Used for `inline` config dictionaries and as the
 * reference implementation for validating the production binary format.
 *
 * Retrieval is delegated to [SortedTableRetrieval], the shared sorted-table
 * algorithm also used by the `.sok` binary dictionary, so the two backends
 * cannot drift (docs/milestones/m1-dictgen-sok.md §3.3).
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

    private val retrieval = SortedTableRetrieval(
        entryCount = entries.size,
        maxKeyLength = entries.maxOfOrNull { it.key.length } ?: 0,
        keyAt = { entries[it].key },
        defaultValueAt = { entries[it].candidates.first() },
    )

    override val maxKeyLength: Int get() = retrieval.maxKeyLength

    override fun matchExact(key: String): DictionaryEntry? =
        retrieval.indexOf(key).let { if (it < 0) null else entries[it] }

    override fun matchPrefix(text: CharArray, start: Int, end: Int): PrefixMatch? =
        retrieval.matchPrefix(text, start, end)

    override fun mayStartKey(codePoint: Int): Boolean = retrieval.mayStartKey(codePoint)

    public companion object {
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
