package com.generalk1ng.sokkuri.dictgen

import com.generalk1ng.sokkuri.Config

/**
 * The closure checks of m1-dictgen-sok §3.6: the packaged config set must
 * be exactly the 16 profiles of [Config] (invariant I8 — a mismatch means a
 * profile was added or removed upstream and needs a human decision), and
 * the compiled dictionary set must be exactly what those configs reference
 * (no dangling references, no orphaned files).
 */
internal object ConsistencyCheck {

    /** [generatedStems] must equal `Config.entries`' stems, both directions. */
    internal fun verifyConfigStems(generatedStems: Set<String>) {
        val expected = Config.entries.map { it.stem }.toSet()
        if (expected == generatedStems) return
        throw DictgenException(buildString {
            append("generated configs and Config.entries disagree (I8):\n")
            for (stem in (expected - generatedStems).sorted()) {
                append("  missing config: ").append(stem).append(".json\n")
            }
            for (stem in (generatedStems - expected).sorted()) {
                append("  unexpected config: ").append(stem).append(".json")
                append(" (not in Config; add it to the enum deliberately)\n")
            }
        })
    }

    /**
     * [referenced] (dictionary basenames referenced by the packaged configs)
     * must equal [generated] (compiled dictionary basenames).
     */
    internal fun verifyDictionaryClosure(referenced: Set<String>, generated: Set<String>) {
        if (referenced == generated) return
        throw DictgenException(buildString {
            append("dictionary set is not closed:\n")
            for (name in (referenced - generated).sorted()) {
                append("  referenced but not generated: ").append(name).append('\n')
            }
            for (name in (generated - referenced).sorted()) {
                append("  generated but not referenced: ").append(name).append('\n')
            }
        })
    }
}
