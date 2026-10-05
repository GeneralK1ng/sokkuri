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

package com.generalk1ng.sokkuri.resource

import com.generalk1ng.sokkuri.SokkuriException
import com.generalk1ng.sokkuri.SokkuriInternalApi

import com.generalk1ng.sokkuri.engine.Dictionary

/**
 * Decoder for one physical dictionary representation, the extension seam of
 * the resource layer: adding a format (e.g. the production `.sok` binary)
 * never touches config parsing or the engine.
 */
@SokkuriInternalApi
public interface DictionaryFormat {
    /** The configuration `type` value selecting this format. */
    public val type: String

    /** Decodes [bytes] into an immutable, thread-safe [Dictionary]. */
    public fun decode(bytes: ByteArray): Dictionary
}

/**
 * A format that exists in the type registry only to fail with a precise,
 * actionable error (e.g. OpenCC's `.ocd2` marisa binaries, which are
 * byte-order/word-size dependent by design and therefore not portable).
 */
@SokkuriInternalApi
public class UnsupportedDictionaryFormat public constructor(
    override val type: String,
    private val reason: String,
) : DictionaryFormat {
    override fun decode(bytes: ByteArray): Dictionary {
        throw SokkuriException.Unsupported("dictionary type '$type': $reason")
    }
}

/** Formats understood by [ResourceDictionaryProvider]. */
@OptIn(SokkuriInternalApi::class)
public val DefaultDictionaryFormats: Map<String, DictionaryFormat> = mapOf(
    TextDictionaryFormat.type to TextDictionaryFormat,
    SokDictionaryFormat.type to SokDictionaryFormat,
    "ocd2" to UnsupportedDictionaryFormat(
        "ocd2",
        "marisa-trie binaries are not portable across platforms; " +
            "use the .sok dictionaries shipped with this library",
    ),
    "ocd" to UnsupportedDictionaryFormat(
        "ocd",
        "legacy Darts format is not supported; use the .sok dictionaries shipped with this library",
    ),
)
