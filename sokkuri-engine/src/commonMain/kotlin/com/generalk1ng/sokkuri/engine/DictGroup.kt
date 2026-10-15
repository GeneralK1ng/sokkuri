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
 * How a [DictGroup] resolves matches across its children — the port of
 * OpenCC's `DictGroupMatchPolicy`.
 */
@SokkuriInternalApi
public enum class GroupMatchPolicy {
    /**
     * Query children in order and return the first child with any match.
     * A shorter prefix from an earlier child wins over a longer prefix
     * from a later child.
     */
    SHORT_CIRCUIT,

    /**
     * The longest prefix across all children wins; child order breaks ties.
     */
    UNION,
}

/**
 * An ordered group of dictionaries, the port of OpenCC's `DictGroup` /
 * `UnionDictGroup`. Children must be non-empty (the config layer drops
 * groups whose children were all filtered out, mirroring OpenCC).
 */
@SokkuriInternalApi
public class DictGroup public constructor(
    private val children: List<Dictionary>,
    private val policy: GroupMatchPolicy,
) : Dictionary {

    init {
        require(children.isNotEmpty()) { "DictGroup requires at least one child dictionary" }
    }

    override val maxKeyLength: Int = children.maxOf { it.maxKeyLength }

    override fun matchExact(key: String): DictionaryEntry? {
        for (child in children) {
            val hit = child.matchExact(key)
            if (hit != null) return hit
        }
        return null
    }

    override fun matchPrefix(text: CharArray, start: Int, end: Int): PrefixMatch? {
        return when (policy) {
            GroupMatchPolicy.SHORT_CIRCUIT -> {
                for (child in children) {
                    val hit = child.matchPrefix(text, start, end)
                    if (hit != null) return hit
                }
                null
            }
            GroupMatchPolicy.UNION -> {
                var best: PrefixMatch? = null
                for (child in children) {
                    val hit = child.matchPrefix(text, start, end)
                    if (hit != null && (best == null || hit.length > best.length)) {
                        best = hit
                    }
                }
                best
            }
        }
    }

    /**
     * [matchPrefix]'s length without materializing any child's value:
     * the first child that matches under SHORT_CIRCUIT, the longest match
     * under UNION (ties have one length, so child order cannot change it).
     */
    override fun matchLength(text: CharArray, start: Int, end: Int): Int {
        var best = -1
        for (child in children) {
            val length = child.matchLength(text, start, end)
            if (policy == GroupMatchPolicy.SHORT_CIRCUIT && length >= 0) return length
            if (length > best) best = length
        }
        return best
    }

    /**
     * The union of the children's first code points, merged on first use,
     * so [mayStartKey], which a conversion runs for every character it
     * bulk-skips, costs one binary search instead of one per child (three
     * on s2t's first stage, nested union included). `null` when a child
     * cannot enumerate its set; [mayStartKey] then asks the children.
     * Read into a local once per call, as the `lazy` delegate's field is
     * volatile.
     *
     * Only a group whose [mayStartKey] is queried keeps a set: one that is
     * merely a child, such as s2t's nested union, merges afresh for its
     * parent in [firstCodePoints] and retains nothing. Each converter owns
     * its groups, so a kept set costs every converter instance: s2t keeps
     * one set of 6,204 code points, about 24 KiB.
     */
    private val firstCodePointSet: IntArray? by lazy(LazyThreadSafetyMode.PUBLICATION) {
        mergeFirstCodePoints()
    }

    override fun firstCodePoints(): IntArray? = mergeFirstCodePoints()

    /**
     * Merges the children's sets, each ascending and distinct by the
     * [Dictionary.firstCodePoints] contract, pairwise in linear time:
     * merging runs on a converter's first conversion, where a sort was
     * measurable in a fresh JVM.
     */
    private fun mergeFirstCodePoints(): IntArray? {
        var merged = IntArray(0)
        for (child in children) {
            val set = child.firstCodePoints() ?: return null
            merged = mergeAscending(merged, set)
        }
        return merged
    }

    private fun mergeAscending(a: IntArray, b: IntArray): IntArray {
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

    override fun mayStartKey(codePoint: Int): Boolean {
        val codePoints = firstCodePointSet ?: return children.any { it.mayStartKey(codePoint) }
        return indexOfCodePoint(codePoints, codePoint) >= 0
    }
}
