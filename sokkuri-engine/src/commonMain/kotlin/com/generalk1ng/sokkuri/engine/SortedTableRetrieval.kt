package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.SokkuriInternalApi

/**
 * Storage-agnostic access to a code-point-sorted key table. One
 * implementation per lexicon storage ([String] entries, `.sok` UTF-8
 * blobs), consumed by [SortedTableRetrieval] so the lookup algorithm has a
 * single source of truth while each backend reads its native representation
 * — the String backend returns existing references, the byte backend decodes
 * nothing during probing (m3-string-view.md §3.3).
 *
 * All operations take an entry [index] into the table, whose keys must be
 * sorted by [Utf.compareByCodePoint], unique, and non-empty (invariant I2).
 * Implementations must be immutable after construction and safe for
 * concurrent use.
 */
@SokkuriInternalApi
public interface SortedKeyTable {

    /** Number of entries in the table. */
    public val entryCount: Int

    /** Longest key length in UTF-16 code units (invariant I1). */
    public val maxKeyLength: Int

    /** First code point of the key at [index]. */
    public fun keyFirstCodePointAt(index: Int): Int

    /** UTF-16 code-unit length of the key at [index] (invariant I1). */
    public fun keyUtf16LengthAt(index: Int): Int

    /**
     * Code-point-ordered comparison of the key at [index] against the
     * `text[start, end)` window — [Utf.compareByCodePoint] between the key
     * and the window, sign included (negative when the key sorts before the
     * window). Powers binary search without materializing either side.
     */
    public fun compareKeyAt(index: Int, text: CharArray, start: Int, end: Int): Int

    /** Whether the key at [index] equals the start of the `text[start, end)` window. */
    public fun keyPrefixesWindowAt(index: Int, text: CharArray, start: Int, end: Int): Boolean

    /** Default candidate (first) of the entry at [index]. Materializing by design: only called on an actual match. */
    public fun defaultValueAt(index: Int): String
}

/**
 * Retrieval over a code-point-sorted key table, the single source of truth
 * for OpenCC `TextDict`'s lookup shape (first-code-point lower bound, then
 * an in-group longest-prefix scan, with `mayStartKey` backed by the
 * first-code-point set).
 *
 * Extracted as a shared component so every lexicon backend delegates to the
 * same algorithm and retrieval semantics cannot drift between them
 * (docs/milestones/m1-dictgen-sok.md §3.3): [SortedListDictionary] and the
 * `.sok` binary dictionary both supply a [SortedKeyTable].
 *
 * Ordering invariants (I2): the table must be sorted by
 * [Utf.compareByCodePoint] with unique, non-empty keys; both the binary
 * search in [indexOf] and the group scan in [matchPrefix] rely on it.
 *
 * Step 2 of m3-string-view.md generalized this class from `keyAt`/
 * `defaultValueAt` string callbacks to [SortedKeyTable]: the algorithm is
 * unchanged, but a byte-backed table now probes without decoding.
 */
@SokkuriInternalApi
public class SortedTableRetrieval private constructor(
    private val table: SortedKeyTable,
) {

    /** Longest key length in UTF-16 code units (invariant I1). */
    public val maxKeyLength: Int get() = table.maxKeyLength

    private val firstCodePoints: Set<Int> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildSet {
            for (index in 0 until table.entryCount) {
                add(table.keyFirstCodePointAt(index))
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
        val probe = key.toCharArray()
        var lo = 0
        var hi = table.entryCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (table.compareKeyAt(mid, probe, 0, probe.size) < 0) lo = mid + 1 else hi = mid
        }
        return if (lo < table.entryCount && table.compareKeyAt(lo, probe, 0, probe.size) == 0) lo else -1
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
        while (index < table.entryCount) {
            // Keys are code-point ordered: once the first code point
            // diverges, no further entry can prefix this text.
            if (table.keyFirstCodePointAt(index) != first) break
            // A prefix hit is implicitly within the window (the check
            // exhausts at maxEnd), so the length scan runs only for actual
            // hits — group entries pay one fused scan, not two.
            if (table.keyPrefixesWindowAt(index, text, start, maxEnd)) {
                val keyLength = table.keyUtf16LengthAt(index)
                if (best == null || keyLength > best.length) {
                    best = PrefixMatch(keyLength, table.defaultValueAt(index))
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
        var hi = table.entryCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (table.keyFirstCodePointAt(mid) < codePoint) lo = mid + 1 else hi = mid
        }
        return lo
    }

    public companion object {

        /**
         * Creates a retrieval over [table]. Named factory because the
         * primary constructor is private, keeping construction at the
         * two backend call sites.
         */
        public fun over(table: SortedKeyTable): SortedTableRetrieval = SortedTableRetrieval(table)
    }
}
