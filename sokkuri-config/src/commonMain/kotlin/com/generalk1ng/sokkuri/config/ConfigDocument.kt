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
        // Present only to reject it: OpenCC's LoadInlineDict errors when an
        // inline dictionary carries may_output_tofu.
        @SerialName("may_output_tofu") val mayOutputTofu: Boolean = false,
    ) : DictDocument()

    @Serializable
    @SerialName("group")
    public data class Group public constructor(
        @SerialName("match_policy") val matchPolicy: String = "short_circuit",
        val dicts: List<DictDocument>,
        @SerialName("may_output_tofu") val mayOutputTofu: Boolean = false,
    ) : DictDocument()
}
