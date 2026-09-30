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

    override fun mayStartKey(codePoint: Int): Boolean = children.any { it.mayStartKey(codePoint) }
}
