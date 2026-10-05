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
 * A lexicon entry: one key with one or more ordered conversion candidates.
 * The first candidate is the default output, mirroring OpenCC's
 * `DictEntry::GetDefault`.
 */
@SokkuriInternalApi
public interface DictionaryEntry {
    public val key: String
    public val candidates: List<String>
}

/**
 * The dictionary abstraction used by the engine.
 *
 * Implementations must be immutable after construction and safe for
 * concurrent use — converters share dictionary instances across threads.
 *
 * All indices are UTF-16 code-unit positions into the input `CharArray`;
 * all lengths are code-unit counts.
 */
@SokkuriInternalApi
public interface Dictionary {

    /** Length of the longest key, in UTF-16 code units. */
    public val maxKeyLength: Int

    /**
     * Returns the entry for an exact [key], or `null`. Used by inspection
     * and candidate-enumeration features; the hot conversion path only
     * needs [matchPrefix].
     */
    public fun matchExact(key: String): DictionaryEntry?

    /**
     * Returns the longest dictionary key that prefixes `text[start, end)`,
     * or `null`. The result carries the key's default candidate.
     */
    public fun matchPrefix(text: CharArray, start: Int, end: Int): PrefixMatch?

    /**
     * Hot-path form of [matchPrefix] with direct write-out
     * (m3-string-view.md §3.4): on a match, appends the key's default
     * candidate to [out] and returns the matched length in UTF-16 code
     * units; on a miss returns `-1` and writes nothing. Semantically
     * identical to `matchPrefix` + `out.append(value)` — the default
     * implementation is exactly that — so storage-aware backends can
     * override to skip the intermediate string. [Conversion] calls this;
     * inspection and grouping stay on [matchPrefix].
     */
    public fun matchAppend(text: CharArray, start: Int, end: Int, out: StringBuilder): Int {
        val match = matchPrefix(text, start, end) ?: return -1
        out.append(match.value)
        return match.length
    }

    /**
     * Whether [codePoint] can begin any key of this dictionary. Powers the
     * conversion loop's bulk-skip optimization (OpenCC's `Utf8SkipTable`):
     * text runs made of characters that cannot start a key are copied out
     * without per-character prefix queries.
     */
    public fun mayStartKey(codePoint: Int): Boolean
}

/** Result of a successful longest-prefix match. */
@SokkuriInternalApi
public class PrefixMatch public constructor(
    /** Matched key length in UTF-16 code units. */
    public val length: Int,
    /** Default candidate for the matched key. */
    public val value: String,
)
