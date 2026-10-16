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
    children: List<Dictionary>,
    private val policy: GroupMatchPolicy,
) : Dictionary {

    init {
        require(children.isNotEmpty()) { "DictGroup requires at least one child dictionary" }
    }

    override val maxKeyLength: Int = children.maxOf { it.maxKeyLength }

    /**
     * The children, walked by index on every lookup: a `for` over a [List]
     * allocates an iterator per call, which the JVM's escape analysis removes
     * but Kotlin/Native does not; on s2t that allocation was about a tenth of
     * the Native conversion time.
     */
    private val members: Array<Dictionary> = children.toTypedArray()

    override fun matchExact(key: String): DictionaryEntry? {
        for (index in members.indices) {
            val hit = members[index].matchExact(key)
            if (hit != null) return hit
        }
        return null
    }

    override fun matchPrefix(text: CharArray, start: Int, end: Int): PrefixMatch? {
        return when (policy) {
            GroupMatchPolicy.SHORT_CIRCUIT -> {
                for (index in members.indices) {
                    val hit = members[index].matchPrefix(text, start, end)
                    if (hit != null) return hit
                }
                null
            }
            GroupMatchPolicy.UNION -> {
                var best: PrefixMatch? = null
                for (index in members.indices) {
                    val hit = members[index].matchPrefix(text, start, end)
                    if (hit != null && (best == null || hit.length > best.length)) {
                        best = hit
                    }
                }
                best
            }
        }
    }

    /**
     * [matchPrefix] with the winning value written straight into [out].
     * Under SHORT_CIRCUIT the first child that matches is the winner, so its
     * own [Dictionary.matchAppend] decides and writes in one retrieval, and a
     * `.sok` child decodes its value directly into [out] without an
     * intermediate string. Under UNION the winner is known only once every
     * child has answered, and asking it again to write cost more than
     * materializing its value once, so UNION stays on [matchPrefix].
     */
    override fun matchAppend(text: CharArray, start: Int, end: Int, out: StringBuilder): Int {
        if (policy == GroupMatchPolicy.UNION) {
            val match = matchPrefix(text, start, end) ?: return -1
            out.append(match.value)
            return match.length
        }
        for (index in members.indices) {
            // A child that misses writes nothing (the matchAppend contract).
            val length = members[index].matchAppend(text, start, end, out)
            if (length >= 0) return length
        }
        return -1
    }

    /**
     * [matchPrefix]'s length without materializing any child's value:
     * the first child that matches under SHORT_CIRCUIT, the longest match
     * under UNION (ties have one length, so child order cannot change it).
     */
    override fun matchLength(text: CharArray, start: Int, end: Int): Int {
        var best = -1
        for (index in members.indices) {
            val length = members[index].matchLength(text, start, end)
            if (policy == GroupMatchPolicy.SHORT_CIRCUIT && length >= 0) return length
            if (length > best) best = length
        }
        return best
    }

    /**
     * The union of the children's key starts, or `null` when a child cannot
     * enumerate its own. Computed on each call and kept by no one here: the
     * stage that bulk-skips over this group takes it once, when it is built,
     * so a group nested in another, such as s2t's union inside its
     * short-circuit group, costs nothing per converter.
     */
    override fun keyStarts(): CodePointSet? {
        var union = CodePointSet.EMPTY
        for (index in members.indices) {
            union = union.union(members[index].keyStarts() ?: return null)
        }
        return union
    }

    /**
     * Whether any child has a key starting with [codePoint]. Asks each child
     * in turn: a conversion stage answers its bulk skip from [keyStarts]
     * instead, so this is not asked per character.
     */
    override fun mayStartKey(codePoint: Int): Boolean = members.any { it.mayStartKey(codePoint) }
}
