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

package com.generalk1ng.sokkuri.engine

import com.generalk1ng.sokkuri.SokkuriInternalApi

/**
 * Storage-agnostic access to a code-point-sorted key table. One
 * implementation per lexicon storage ([String] entries, `.sok` UTF-8
 * blobs), consumed by [SortedTableRetrieval] so the lookup algorithm has a
 * single source of truth while each backend reads its native representation
 * — the String backend returns existing references, the byte backend decodes
 * nothing during probing.
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

    /**
     * Code-point-ordered comparison of the key at [index] against the
     * `text[start, end)` window — [Utf.compareByCodePoint] between the key
     * and the window, sign included (negative when the key sorts before the
     * window). Powers binary search without materializing either side.
     */
    public fun compareKeyAt(index: Int, text: CharArray, start: Int, end: Int): Int

    /**
     * Code point of the key at [index] that begins [offset] native units
     * into the key, or `-1` when the key ends exactly at [offset]. Native
     * units are the table's key encoding: UTF-16 code units for String
     * keys, UTF-8 bytes for `.sok`. Retrieval asks only at offsets on a
     * code-point boundary, reached by summing [encodedWidth] over the code
     * points it has already matched in that key.
     */
    public fun keyCodePointAt(index: Int, offset: Int): Int

    /** Native units [codePoint] occupies in this table's key encoding. */
    public fun encodedWidth(codePoint: Int): Int

    /** Default candidate (first) of the entry at [index]. Materializing by design: only called on an actual match. */
    public fun defaultValueAt(index: Int): String

    /**
     * Appends the default candidate of the entry at [index] to [out] — the
     * zero-materialization form of [defaultValueAt]: byte-backed tables
     * decode straight into the output buffer instead of producing an
     * intermediate string.
     */
    public fun appendDefaultValueAt(index: Int, out: StringBuilder)
}

/**
 * Retrieval over a code-point-sorted key table: the single implementation
 * of exact and longest-prefix lookup, shared so every lexicon backend
 * delegates to the same algorithm and retrieval semantics cannot drift
 * between them. [SortedListDictionary] and the `.sok` binary dictionary
 * both supply a [SortedKeyTable], and a byte-backed table probes without
 * decoding a key.
 *
 * Longest-prefix lookup narrows a range instead of scanning one: keys that
 * share a prefix are contiguous in code-point order and sorted by their
 * next code point, with the key that ends at the prefix first (`-1` from
 * [SortedKeyTable.keyCodePointAt] sorts before every code point). The
 * first input code point selects its group through the first-code-point
 * index; every further code point narrows the range by binary search over
 * the keys' code point at the current offset, and a range that starts with
 * a key ending there is a hit. The last hit is the longest key prefixing
 * the window, the entry upstream's `Dict::MatchPrefix` returns. A match of
 * k code points in a group of g keys costs O(k log g) single-code-point
 * probes; the scan this replaced compared every key of the group at every
 * position: about 53 whole keys per input character of the benchmark's
 * `medium` sample against STPhrases.
 *
 * Ordering invariants (I2): the table must be sorted by
 * [Utf.compareByCodePoint] with unique, non-empty keys; the binary search
 * in [indexOf], the first-code-point index and the narrowing all rely on it.
 */
@SokkuriInternalApi
public class SortedTableRetrieval private constructor(
    private val table: SortedKeyTable,
) {

    /** Longest key length in UTF-16 code units (invariant I1). */
    public val maxKeyLength: Int get() = table.maxKeyLength

    /**
     * The groups of keys sharing a first code point: [codePoints] holds
     * the distinct first code points in ascending order, and group `i`
     * spans entries `[groupStarts[i], groupStarts[i + 1])`. Plain IntArrays,
     * so lookups binary-search without boxing.
     */
    private class FirstCodePointIndex(val codePoints: IntArray, val groupStarts: IntArray)

    /**
     * Built once per dictionary, on first use. Callers read it into a
     * local once per call: the `lazy` delegate publishes through a
     * volatile field, and a volatile read cannot be hoisted out of a loop
     * (measured ~15% on s2t when read inside the loop).
     */
    private val firstCodePointIndex: FirstCodePointIndex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val codePoints = IntArray(table.entryCount)
        val groupStarts = IntArray(table.entryCount + 1)
        var groups = 0
        for (index in 0 until table.entryCount) {
            val first = table.keyFirstCodePointAt(index)
            if (groups == 0 || codePoints[groups - 1] != first) {
                codePoints[groups] = first
                groupStarts[groups] = index
                groups += 1
            }
        }
        groupStarts[groups] = table.entryCount
        FirstCodePointIndex(codePoints.copyOf(groups), groupStarts.copyOf(groups + 1))
    }

    /**
     * The distinct first code points of the table's keys, ascending: a
     * copy of the index, for [Dictionary.firstCodePoints].
     */
    public fun firstCodePoints(): IntArray = firstCodePointIndex.codePoints.copyOf()

    /** Whether [codePoint] begins any key of the table. */
    public fun mayStartKey(codePoint: Int): Boolean = indexOfCodePoint(firstCodePointIndex.codePoints, codePoint) >= 0

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
        val found = findLongestPrefixIndex(text, start, end)
        if (found < 0L) return null
        return PrefixMatch(length = found.toInt(), value = table.defaultValueAt((found ushr 32).toInt()))
    }

    /**
     * The length-only form of [matchPrefix]: the matched length in UTF-16
     * code units, or `-1`, without touching the entry's value.
     */
    public fun matchLength(text: CharArray, start: Int, end: Int): Int {
        val found = findLongestPrefixIndex(text, start, end)
        return if (found < 0L) -1 else found.toInt()
    }

    /**
     * The sink form of [matchPrefix]: appends the
     * winning entry's default candidate straight to [out] and returns the
     * matched length in UTF-16 code units, or `-1` when nothing matches.
     * The scan and winner selection are byte-identical to [matchPrefix] —
     * both funnel through [findLongestPrefixIndex] — so the two can never
     * diverge; only the value's exit path differs (materialized vs written).
     */
    public fun matchAppend(text: CharArray, start: Int, end: Int, out: StringBuilder): Int {
        val found = findLongestPrefixIndex(text, start, end)
        if (found < 0L) return -1
        table.appendDefaultValueAt((found ushr 32).toInt(), out)
        return found.toInt()
    }

    /**
     * Shared longest-prefix lookup, by narrowing (see the class
     * documentation). Returns the winning `(entryIndex, keyLength)` packed
     * into a Long (high 32 bits index, low 32 bits length in UTF-16 code
     * units), or `-1` when no key prefixes the window.
     */
    private fun findLongestPrefixIndex(text: CharArray, start: Int, end: Int): Long {
        if (start >= end) return -1L
        val index = firstCodePointIndex
        val first = Utf.codePointAt(text, start, end)
        val group = indexOfCodePoint(index.codePoints, first)
        if (group < 0) return -1L

        var lo = index.groupStarts[group]
        var hi = index.groupStarts[group + 1]
        var offset = table.encodedWidth(first)
        var position = start + Utf.charCount(first)
        var bestIndex = -1
        var bestLength = -1
        if (table.keyCodePointAt(lo, offset) < 0) {
            bestIndex = lo
            bestLength = position - start
        }
        val maxEnd = minOf(end, start + maxKeyLength)
        while (position < maxEnd) {
            val codePoint = Utf.codePointAt(text, position, end)
            lo = lowerBound(lo, hi, offset, codePoint)
            if (lo == hi || table.keyCodePointAt(lo, offset) != codePoint) break
            hi = upperBound(lo, hi, offset, codePoint)
            offset += table.encodedWidth(codePoint)
            position += Utf.charCount(codePoint)
            if (table.keyCodePointAt(lo, offset) < 0) {
                bestIndex = lo
                bestLength = position - start
            }
        }
        return if (bestIndex < 0) -1L else (bestIndex.toLong() shl 32) or bestLength.toLong()
    }

    /** First entry in `[lo, hi)` whose code point at [offset] is not below [codePoint]. */
    private fun lowerBound(lo: Int, hi: Int, offset: Int, codePoint: Int): Int {
        var a = lo
        var b = hi
        while (a < b) {
            val mid = (a + b) ushr 1
            if (table.keyCodePointAt(mid, offset) < codePoint) a = mid + 1 else b = mid
        }
        return a
    }

    /** First entry in `[lo, hi)` whose code point at [offset] is above [codePoint]. */
    private fun upperBound(lo: Int, hi: Int, offset: Int, codePoint: Int): Int {
        var a = lo
        var b = hi
        while (a < b) {
            val mid = (a + b) ushr 1
            if (table.keyCodePointAt(mid, offset) <= codePoint) a = mid + 1 else b = mid
        }
        return a
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

/**
 * Index of [codePoint] in [codePoints] (ascending, distinct), or `-1`.
 * Shared by [SortedTableRetrieval] and [DictGroup] for their
 * first-code-point sets. Hand-rolled binary search rather than the
 * stdlib's `IntArray.binarySearch`: the stdlib overloads are not visible to
 * every backend's common compilation (K/N and metadata compilation
 * rejected them while JVM accepted).
 *
 * Inline because [Dictionary.mayStartKey] runs it for every character a
 * conversion bulk-skips: as an out-of-line call it cost Kotlin/Native 3-4%
 * on skip-dominated profiles (t2tw), which the JVM's JIT hid. For the same
 * reason a code point outside the set's range is rejected before
 * searching: most text never reaches the key range of a narrow table, such
 * as the CJK compatibility normalization, whose keys all lie at U+F900 and
 * above.
 */
@Suppress("NOTHING_TO_INLINE")
internal inline fun indexOfCodePoint(codePoints: IntArray, codePoint: Int): Int {
    var hi = codePoints.size
    if (hi == 0 || codePoint < codePoints[0] || codePoint > codePoints[hi - 1]) return -1
    var lo = 0
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (codePoints[mid] < codePoint) lo = mid + 1 else hi = mid
    }
    return if (lo < codePoints.size && codePoints[lo] == codePoint) lo else -1
}
