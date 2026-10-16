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
 * An immutable set of code points with constant-time membership and rank in
 * the Basic Multilingual Plane: the structure behind every first-code-point
 * question the engine asks, once per bulk-skipped character and once per
 * retrieval.
 *
 * It ports the character-level half of OpenCC's `Utf8SkipTable`, one bit per
 * BMP code point, with two refinements:
 *
 * - the bitmap covers only the 64-code-point words from the lowest to the
 *   highest BMP member, so a set confined to the CJK blocks or to the
 *   compatibility ideographs costs a fraction of the plane's 8 KiB;
 * - a running member count per word answers [indexOf], the member's position
 *   in ascending order, without a search, which is how [SortedTableRetrieval]
 *   finds the group of keys sharing a first code point.
 *
 * Supplementary-plane members, rare in the dictionaries, are kept apart in an
 * ascending array and found by binary search; upstream filters them by lead
 * byte only.
 *
 * Members are code points in `0..0x10FFFF`, surrogate values included, since a
 * lenient key may begin with an unpaired surrogate. Queries accept any [Int]
 * and answer outside that range as non-members. Two sets with the same members
 * have the same representation, so [equals] compares members.
 */
@SokkuriInternalApi
public class CodePointSet private constructor(
    /** Index of the first 64-code-point word [words] covers; 0 when there is no BMP member. */
    @PublishedApi internal val firstWord: Int,
    /** BMP members: code point `c` is bit `c and 63` of word `(c ushr 6) - firstWord`. */
    @PublishedApi internal val words: LongArray,
    /** Supplementary-plane members, ascending and distinct. */
    @PublishedApi internal val supplementary: IntArray,
) {

    /** BMP members in the words before each word, for [indexOf]. */
    private val ranks = IntArray(words.size)

    private val bmpSize: Int

    init {
        var members = 0
        for (word in words.indices) {
            ranks[word] = members
            members += words[word].countOneBits()
        }
        bmpSize = members
    }

    /** Number of members. */
    public val size: Int get() = bmpSize + supplementary.size

    /**
     * Whether [codePoint] is a member. Inline because a bulk skip asks it for
     * every character it passes: as a call, Kotlin/Native spent about a third
     * of a skip-dominated conversion entering and leaving it.
     */
    @Suppress("NOTHING_TO_INLINE")
    public inline operator fun contains(codePoint: Int): Boolean {
        if (codePoint >= BMP_LIMIT) return supplementaryContains(codePoint)
        // A negative code point shifts to a huge word index and fails the bound check.
        val word = (codePoint ushr 6) - firstWord
        return word >= 0 && word < words.size && ((words[word] ushr (codePoint and 63)) and 1L) != 0L
    }

    /**
     * Position of [codePoint] among the members in ascending order, or `-1`
     * when it is not a member.
     */
    public fun indexOf(codePoint: Int): Int {
        if (codePoint >= BMP_LIMIT) {
            val at = searchSupplementary(codePoint)
            return if (at < 0) -1 else bmpSize + at
        }
        val word = (codePoint ushr 6) - firstWord
        if (word < 0 || word >= words.size) return -1
        val bits = words[word]
        val bit = 1L shl (codePoint and 63)
        if ((bits and bit) == 0L) return -1
        return ranks[word] + (bits and (bit - 1)).countOneBits()
    }

    /** The members in ascending order, as a fresh array. */
    public fun toIntArray(): IntArray {
        val out = IntArray(size)
        var next = 0
        for (word in words.indices) {
            var bits = words[word]
            while (bits != 0L) {
                val low = bits.countTrailingZeroBits()
                out[next++] = ((firstWord + word) shl 6) + low
                bits = bits and (bits - 1)
            }
        }
        supplementary.copyInto(out, next)
        return out
    }

    /** The set of code points that are members of this set or of [other]. */
    public fun union(other: CodePointSet): CodePointSet {
        if (other.size == 0) return this
        if (size == 0) return other
        val first = when {
            words.isEmpty() -> other.firstWord
            other.words.isEmpty() -> firstWord
            else -> minOf(firstWord, other.firstWord)
        }
        val end = maxOf(endWord(), other.endWord())
        val merged = LongArray(maxOf(0, end - first))
        copyWordsInto(merged, first)
        other.copyWordsInto(merged, first)
        return CodePointSet(first, merged, mergeAscending(supplementary, other.supplementary))
    }

    /** One past the last word [words] covers, in plane-wide word indices. */
    private fun endWord(): Int = if (words.isEmpty()) Int.MIN_VALUE else firstWord + words.size

    private fun copyWordsInto(target: LongArray, targetFirstWord: Int) {
        for (word in words.indices) {
            val at = firstWord + word - targetFirstWord
            target[at] = target[at] or words[word]
        }
    }

    @PublishedApi
    internal fun supplementaryContains(codePoint: Int): Boolean = searchSupplementary(codePoint) >= 0

    /**
     * Index of [codePoint] in [supplementary], or `-1`. Hand-rolled: the
     * stdlib's `IntArray.binarySearch` is not visible to every target's
     * common compilation (Kotlin/Native and metadata compilation reject it).
     */
    private fun searchSupplementary(codePoint: Int): Int {
        var hi = supplementary.size
        if (hi == 0 || codePoint < supplementary[0] || codePoint > supplementary[hi - 1]) return -1
        var lo = 0
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (supplementary[mid] < codePoint) lo = mid + 1 else hi = mid
        }
        return if (supplementary[lo] == codePoint) lo else -1
    }

    override fun equals(other: Any?): Boolean =
        other is CodePointSet &&
            firstWord == other.firstWord &&
            words.contentEquals(other.words) &&
            supplementary.contentEquals(other.supplementary)

    override fun hashCode(): Int =
        (firstWord * 31 + words.contentHashCode()) * 31 + supplementary.contentHashCode()

    override fun toString(): String =
        toIntArray().joinToString(prefix = "CodePointSet[", postfix = "]") {
            "U+" + it.toString(16).uppercase().padStart(4, '0')
        }

    public companion object {

        /** First code point past the Basic Multilingual Plane. */
        @PublishedApi
        internal const val BMP_LIMIT: Int = 0x10000

        private const val MAX_CODE_POINT: Int = 0x10FFFF

        /** The set with no members. */
        public val EMPTY: CodePointSet = CodePointSet(0, LongArray(0), IntArray(0))

        /**
         * The set of [codePoints], given in any order, duplicates allowed.
         *
         * @throws IllegalArgumentException when a code point lies outside `0..0x10FFFF`.
         */
        public fun of(vararg codePoints: Int): CodePointSet {
            var lowestWord = Int.MAX_VALUE
            var highestWord = Int.MIN_VALUE
            var supplementaryCount = 0
            for (codePoint in codePoints) {
                require(codePoint in 0..MAX_CODE_POINT) { "not a code point: $codePoint" }
                if (codePoint < BMP_LIMIT) {
                    lowestWord = minOf(lowestWord, codePoint ushr 6)
                    highestWord = maxOf(highestWord, codePoint ushr 6)
                } else {
                    supplementaryCount++
                }
            }
            val firstWord = if (highestWord < 0) 0 else lowestWord
            val words = LongArray(if (highestWord < 0) 0 else highestWord - lowestWord + 1)
            val supplementary = IntArray(supplementaryCount)
            var next = 0
            for (codePoint in codePoints) {
                if (codePoint < BMP_LIMIT) {
                    val word = (codePoint ushr 6) - firstWord
                    words[word] = words[word] or (1L shl (codePoint and 63))
                } else {
                    supplementary[next++] = codePoint
                }
            }
            supplementary.sort()
            return CodePointSet(firstWord, words, distinctAscending(supplementary))
        }

        /** [sorted] without repeats; it is returned as is when it has none. */
        private fun distinctAscending(sorted: IntArray): IntArray {
            var size = 0
            for (index in sorted.indices) {
                if (index == 0 || sorted[index] != sorted[index - 1]) sorted[size++] = sorted[index]
            }
            return if (size == sorted.size) sorted else sorted.copyOf(size)
        }

        /** The members of two ascending, distinct arrays, ascending and distinct, in linear time. */
        private fun mergeAscending(a: IntArray, b: IntArray): IntArray {
            if (a.isEmpty()) return b
            if (b.isEmpty()) return a
            val out = IntArray(a.size + b.size)
            var i = 0
            var j = 0
            var size = 0
            while (i < a.size && j < b.size) {
                val x = a[i]
                val y = b[j]
                out[size++] = if (x <= y) x else y
                if (x <= y) i++
                if (y <= x) j++
            }
            while (i < a.size) out[size++] = a[i++]
            while (j < b.size) out[size++] = b[j++]
            return if (size == out.size) out else out.copyOf(size)
        }
    }
}
