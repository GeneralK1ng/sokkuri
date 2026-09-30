package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.SokkuriInternalApi

/**
 * Retrieval over a code-point-sorted key table, the single source of truth
 * for OpenCC `TextDict`'s lookup shape (first-code-point lower bound, then
 * an in-group longest-prefix scan, with `mayStartKey` backed by the
 * first-code-point set).
 *
 * Extracted as a shared component so every lexicon backend delegates to the
 * same algorithm and retrieval semantics cannot drift between them
 * (docs/milestones/m1-dictgen-sok.md §3.3): [SortedListDictionary] and the
 * `.sok` binary dictionary both wrap this class.
 *
 * Keys are accessed exclusively through [keyAt]/[defaultValueAt] callbacks,
 * so backends with non-[String] storage (e.g. UTF-8 blobs decoded on
 * demand) participate without materializing a parallel key array.
 *
 * Ordering invariants (I2): the table must be sorted by
 * [Utf.compareByCodePoint] with unique, non-empty keys; both the binary
 * search in [indexOf] and the group scan in [matchPrefix] rely on it.
 */
@SokkuriInternalApi
public class SortedTableRetrieval public constructor(
    private val entryCount: Int,
    /** Longest key length in UTF-16 code units (invariant I1). */
    public val maxKeyLength: Int,
    /** Key at [index]; code-point ordered, unique, non-empty. */
    private val keyAt: (index: Int) -> String,
    /** Default candidate (first) of the entry at [index]. */
    private val defaultValueAt: (index: Int) -> String,
) {

    private val firstCodePoints: Set<Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildSet {
            for (index in 0 until entryCount) {
                add(Utf.codePointAt(keyAt(index), 0))
            }
        }
    }

    /** Whether [codePoint] begins any key of the table. */
    public fun mayStartKey(codePoint: Int): Boolean = codePoint in firstCodePoints

    /**
     * Index of [key] in the table, or `-1` when absent. Binary search by
     * code-point order — the same comparator the table is sorted with.
     */
    public fun indexOf(key: String): Int {
        var lo = 0
        var hi = entryCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (Utf.compareByCodePoint(keyAt(mid), key) < 0) lo = mid + 1 else hi = mid
        }
        return if (lo < entryCount && Utf.compareByCodePoint(keyAt(lo), key) == 0) lo else -1
    }

    /**
     * Returns the longest table key that prefixes `text[start, end)`,
     * or `null`. [PrefixMatch.length] is in UTF-16 code units.
     */
    public fun matchPrefix(text: CharArray, start: Int, end: Int): PrefixMatch? {
        if (start >= end) return null
        val first = Utf.codePointAt(text, start, end)
        if (first !in firstCodePoints) return null

        var index = lowerBoundByFirstCodePoint(first)
        var best: PrefixMatch? = null
        val maxEnd = minOf(end, start + maxKeyLength)
        while (index < entryCount) {
            val key = keyAt(index)
            // Keys are code-point ordered: once the first code point
            // diverges, no further entry can prefix this text.
            if (Utf.codePointAt(key, 0) != first) break
            if (key.length <= maxEnd - start && Utf.startsWithAt(text, start, maxEnd, key)) {
                if (best == null || key.length > best.length) {
                    best = PrefixMatch(key.length, defaultValueAt(index))
                }
            }
            index += 1
        }
        return best
    }

    /**
     * First index whose first code point is >= [codePoint]. Equivalent to
     * an [indexOf]-style lower bound against a single-code-point probe,
     * since such a probe sorts before every key sharing its code point.
     */
    private fun lowerBoundByFirstCodePoint(codePoint: Int): Int {
        var lo = 0
        var hi = entryCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (Utf.codePointAt(keyAt(mid), 0) < codePoint) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
