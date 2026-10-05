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

package com.generalk1ng.sokkuri.config

import com.generalk1ng.sokkuri.SokkuriException
import com.generalk1ng.sokkuri.SokkuriInternalApi

import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * The lenient JSON configuration parser shared by config decoding.
 *
 * OpenCC parses configurations with exactly `kParseCommentsFlag |
 * kParseTrailingCommasFlag` (rapidjson, see OpenCC `Config.cpp`) — comments
 * and trailing commas tolerated, strictly-quoted JSON otherwise, unknown keys
 * ignored. Its ecosystem files (including `testcases.json`) are JSONC in
 * practice. kotlinx-serialization natively covers trailing commas; comments
 * are stripped by a small string preprocessor that respects string literals.
 * Deliberately *not* `isLenient`: Sokkuri must not accept malformed configs
 * that upstream OpenCC rejects.
 */
@SokkuriInternalApi
public object JsonSupport {

    public val opencc: Json = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
        // OpenCC configs do not use polymorphism beyond the sealed DictDocument.
        classDiscriminator = "type"
        serializersModule = dictTypeRejection
    }

    /**
     * Removes `//` and block comments outside string literals. String
     * contents, escapes, and line structure are preserved.
     */
    public fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        var inString = false
        while (i < n) {
            val c = text[i]
            when {
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < n) {
                        out.append(text[i + 1])
                        i += 2
                        continue
                    }
                    if (c == '"') inString = false
                    i += 1
                }
                c == '"' -> {
                    inString = true
                    out.append(c)
                    i += 1
                }
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n') i += 1
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i += 1
                    i = minOf(i + 2, n)
                }
                else -> {
                    out.append(c)
                    i += 1
                }
            }
        }
        return out.toString()
    }
}

/**
 * Reports an unrecognized `dict.type` in this library's own words, instead of
 * letting the sealed-class machinery do it (architecture.md §9, D11).
 *
 * `@JsonClassDiscriminator` demotes `type` from data to a serializer-level
 * discriminator, so the config layer never sees the string — only kotlinx can
 * reject it, and its message names `DictDocument`, talks about "polymorphic
 * scope", and advises marking the reader's own class `@Serializable`. None of
 * that is actionable for someone editing a config.
 *
 * A default deserializer provider is consulted exactly when the sealed lookup
 * found no subclass to serve the discriminator — whether it named an unknown
 * type or was absent outright — and that is the only case needing our wording.
 * A known type never reaches here, so this seam enumerates
 * nothing and the sealed hierarchy stays the single source of truth for what
 * is accepted. The failure is [SokkuriException.InvalidConfig] and not
 * `Unsupported` — the split being that a type outside the schema is an invalid
 * config, while a type inside it that this port cannot execute (`ocd`/`ocd2`,
 * rejected by the format registry) is unsupported.
 *
 * Throwing rather than returning a serializer is what keeps the wording ours:
 * the JSON decoder rewraps only `SerializationException`, so a
 * `SokkuriException` arrives with its type intact. The cost is kotlinx's
 * `at path: $.conversion_chain[0].dict` suffix, which the quoted type name
 * makes up for — it is greppable in the offending config.
 */
@OptIn(SokkuriInternalApi::class)
private val dictTypeRejection: SerializersModule = SerializersModule {
    polymorphicDefaultDeserializer(DictDocument::class) { serialName ->
        throw SokkuriException.InvalidConfig(
            if (serialName == null) {
                "dictionary entry has no 'type' field"
            } else {
                "unknown dictionary type '$serialName'"
            },
        )
    }
}
