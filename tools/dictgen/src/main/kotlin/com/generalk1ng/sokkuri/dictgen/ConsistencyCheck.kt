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

package com.generalk1ng.sokkuri.dictgen

import com.generalk1ng.sokkuri.SokkuriConfig

/**
 * The closure checks: the packaged config set must
 * be exactly the 16 profiles of [SokkuriConfig] (invariant I8 — a mismatch means a
 * profile was added or removed upstream and needs a human decision), and
 * the compiled dictionary set must be exactly what those configs reference
 * (no dangling references, no orphaned files).
 */
internal object ConsistencyCheck {

    /** [generatedStems] must equal `SokkuriConfig.entries`' stems, both directions. */
    internal fun verifyConfigStems(generatedStems: Set<String>) {
        val expected = SokkuriConfig.entries.map { it.stem }.toSet()
        if (expected == generatedStems) return
        throw DictgenException(buildString {
            append("generated configs and SokkuriConfig.entries disagree (I8):\n")
            for (stem in (expected - generatedStems).sorted()) {
                append("  missing config: ").append(stem).append(".json\n")
            }
            for (stem in (generatedStems - expected).sorted()) {
                append("  unexpected config: ").append(stem).append(".json")
                append(" (not in SokkuriConfig; add it to the enum deliberately)\n")
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
