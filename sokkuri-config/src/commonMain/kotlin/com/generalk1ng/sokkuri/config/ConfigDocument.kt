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

import com.generalk1ng.sokkuri.SokkuriInternalApi
import kotlinx.serialization.ExperimentalSerializationApi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * JSON model of an OpenCC configuration document (`data/config/<stem>.json`),
 * covering the modern schema used by every built-in profile:
 *
 * ```json
 * {
 *   "name": "...",
 *   "normalization": [ { "dict": {  } } ],
 *   "segmentation":  { "type": "mmseg", "dict": {  } },
 *   "conversion_chain": [ { "dict": {  } } ]
 * }
 * ```
 *
 * Unknown keys are ignored and unknown `segmentation.type` values fail at
 * parse time (only `mmseg` is ported), matching OpenCC's behavior of
 * rejecting unresolvable segmenters.
 */
@Serializable
@SokkuriInternalApi
public data class ConfigDocument(
    val name: String? = null,
    val normalization: List<ConversionDocument> = emptyList(),
    val segmentation: SegmentationDocument? = null,
    @SerialName("conversion_chain") val conversionChain: List<ConversionDocument>,
)

/** One entry of `conversion_chain` / `normalization`: a single dictionary. */
@Serializable
@SokkuriInternalApi
public data class ConversionDocument(
    val dict: DictDocument,
)

/** The `segmentation` object; plugin segmenters are not supported. */
@Serializable
@SokkuriInternalApi
public data class SegmentationDocument(
    val type: String,
    val dict: DictDocument? = null,
)

/**
 * The `dict` node. File-backed dictionaries carry an optional
 * `may_output_tofu` flag (excluded unless requested via [com.generalk1ng.sokkuri.SokkuriOptions]).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
@SokkuriInternalApi
public sealed class DictDocument {

    @Serializable
    @SerialName("text")
    public data class Text public constructor(
        val file: String,
        @SerialName("may_output_tofu") val mayOutputTofu: Boolean = false,
    ) : DictDocument()

    @Serializable
    @SerialName("ocd2")
    public data class Ocd2 public constructor(
        val file: String,
        @SerialName("may_output_tofu") val mayOutputTofu: Boolean = false,
    ) : DictDocument()

    @Serializable
    @SerialName("ocd")
    public data class Ocd public constructor(
        val file: String,
        @SerialName("may_output_tofu") val mayOutputTofu: Boolean = false,
    ) : DictDocument()

    /**
     * The `.sok` binary format produced by `tools:dictgen` — the production
     * dictionary type of this library, replacing OpenCC's `.ocd2` in the
     * packaged configs. Decoded through the `sok` entry of the
     * [com.generalk1ng.sokkuri.resource.DictionaryFormat] registry like any
     * other file-backed type.
     */
    @Serializable
    @SerialName("sok")
    public data class Sok public constructor(
        val file: String,
        @SerialName("may_output_tofu") val mayOutputTofu: Boolean = false,
    ) : DictDocument()

    @Serializable
    @SerialName("inline")
    public data class Inline public constructor(
        val entries: Map<String, String>,
        /**
         * Whether the `may_output_tofu` key is present, not what it says.
         *
         * Upstream `LoadInlineDict` rejects on `doc.HasMember("may_output_tofu")`
         * before it ever reads the value, so an inline dictionary carrying an
         * explicit `false` is rejected exactly like one carrying `true`.
         * Modelled as nullable with no default so presence survives decoding,
         * which a `Boolean` defaulting to false cannot express; the default
         * stays null so the config rewriter omits the key when it writes an
         * inline node back out.
         */
        @SerialName("may_output_tofu") val mayOutputTofu: Boolean? = null,
    ) : DictDocument()

    @Serializable
    @SerialName("group")
    public data class Group public constructor(
        @SerialName("match_policy") val matchPolicy: String = "short_circuit",
        val dicts: List<DictDocument>,
        @SerialName("may_output_tofu") val mayOutputTofu: Boolean = false,
    ) : DictDocument()
}
