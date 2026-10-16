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
 * The closure checks: the clone's profile configs must be exactly the
 * profiles of [SokkuriConfig] plus [UNPORTED_UPSTREAM_STEMS] (invariant I8 — a
 * mismatch means a profile was added or removed upstream and needs a human
 * decision), and the compiled dictionary set must be exactly what the packaged
 * configs reference (no dangling references, no orphaned files).
 */
internal object ConsistencyCheck {

    /**
     * Upstream profiles deliberately not ported: the seal family
     * (docs/architecture/divergences.md #4). Registered by name rather than matched by
     * pattern, so a stale entry fails like any other drift.
     */
    internal val UNPORTED_UPSTREAM_STEMS: Set<String> = setOf("s2seal", "seal2t", "t2seal")

    /**
     * [upstreamStems] (every profile config in the clone's `data/config`) must
     * equal `SokkuriConfig.entries`' stems plus [UNPORTED_UPSTREAM_STEMS], both
     * directions.
     */
    internal fun verifyConfigStems(upstreamStems: Set<String>) {
        val ported = SokkuriConfig.entries.map { it.stem }.toSet()
        val missing = ported - upstreamStems
        val unregistered = upstreamStems - ported - UNPORTED_UPSTREAM_STEMS
        val stale = UNPORTED_UPSTREAM_STEMS - upstreamStems
        if (missing.isEmpty() && unregistered.isEmpty() && stale.isEmpty()) return
        throw DictgenException(buildString {
            append("upstream configs and SokkuriConfig.entries disagree (I8):\n")
            for (stem in missing.sorted()) {
                append("  missing upstream config: ").append(stem).append(".json\n")
            }
            for (stem in unregistered.sorted()) {
                append("  unported upstream config: ").append(stem).append(".json")
                append(" (add it to SokkuriConfig deliberately, or register it as unported)\n")
            }
            for (stem in stale.sorted()) {
                append("  stale unported registration: ").append(stem)
                append(" (no longer shipped upstream)\n")
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
