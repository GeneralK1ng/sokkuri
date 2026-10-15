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

import com.generalk1ng.sokkuri.SokkuriException
import com.generalk1ng.sokkuri.engine.Conversion
import com.generalk1ng.sokkuri.engine.ConversionChain
import com.generalk1ng.sokkuri.engine.Converter
import com.generalk1ng.sokkuri.engine.DictGroup
import com.generalk1ng.sokkuri.engine.Dictionary
import com.generalk1ng.sokkuri.engine.GroupMatchPolicy
import com.generalk1ng.sokkuri.engine.MaxMatchSegmentation
import com.generalk1ng.sokkuri.engine.Segmentation
import com.generalk1ng.sokkuri.engine.SortedListDictionary
import kotlinx.serialization.SerializationException

/**
 * Builds an immutable engine [Converter] from an OpenCC configuration JSON
 * document — the port of OpenCC's `Config`/`ConfigInternal`.
 *
 * Semantics mirrored from the C++ implementation:
 *
 * - `normalization` stages form a pre-pass applied to the whole input
 *   before segmentation, assembled as a [Converter.Normalizing] wrapper
 *   (upstream `ConfigBasedConverter`); an empty array degenerates to the
 *   main converter alone (observationally identical, as an empty chain is
 *   the identity conversion);
 * - `segmentation` must be of type `mmseg`; its dictionary is loaded with
 *   tofu-risk dictionaries *included* (OpenCC's `ParseSegmentation` passes
 *   `includeTofuRiskDictionaries = true`);
 * - each `conversion_chain` stage drops out when its dictionary is excluded
 *   by the tofu policy (`ParseConversion` returning null), and an empty
 *   chain degenerates to identity;
 * - groups honor `match_policy`, defaulting to `short_circuit`, validated
 *   before child filtering (an unknown policy is an error even for groups
 *   whose children all filter out);
 * - `inline` dictionaries are single-candidate, reject `may_output_tofu`
 *   (upstream `LoadInlineDict`), and are validated for non-empty keys and
 *   values.
 */
@SokkuriInternalApi
public class ConfigParser public constructor(
    private val dictionaries: DictionaryProvider,
    private val includeTofuRiskDictionaries: Boolean,
) {

    public fun parse(configJson: String): Converter {
        val reading = JsonStructure.readAsUpstream(JsonSupport.stripComments(configJson))
        val document = decode(reading.json)
        val repeats = reading.repeatedInlineKeys
        val segmentation: Segmentation? = document.segmentation?.let { buildSegmentation(it, repeats) }
        val chain: ConversionChain = buildChain(document.conversionChain, DictPath.CONVERSION_CHAIN, repeats)
        val main = Converter.SingleStage(segmentation, chain)
        val normalization: Converter? = document.normalization
            .takeIf { it.isNotEmpty() }
            ?.let { buildChain(it, DictPath.NORMALIZATION, repeats) }
            ?.let { Converter.SingleStage(segmentation = null, chain = it) }
        return if (normalization != null) {
            Converter.Normalizing(normalization, main)
        } else {
            main
        }
    }

    /** Decodes a config text already prepared as upstream reads it (comments out, first occurrences only). */
    private fun decode(prepared: String): ConfigDocument {
        return try {
            JsonSupport.opencc.decodeFromString<ConfigDocument>(prepared)
        } catch (e: SerializationException) {
            throw SokkuriException.InvalidConfig(e.message ?: "malformed JSON")
        } catch (e: IllegalArgumentException) {
            throw SokkuriException.InvalidConfig(e.message ?: "malformed JSON")
        }
    }

    /*
     * `repeats` maps inline dictionaries, by DictPath, to a key their entries
     * list twice (JsonStructure.readAsUpstream); the builders pass each
     * dictionary's path down so buildInline can reject exactly the ones
     * upstream would load.
     */

    private fun buildSegmentation(document: SegmentationDocument, repeats: Map<String, String>): Segmentation {
        if (document.type != "mmseg") {
            throw SokkuriException.Unsupported("segmentation type '${document.type}'")
        }
        val dictDocument = document.dict
            ?: throw SokkuriException.InvalidConfig("mmseg segmentation requires a dict")
        // OpenCC loads segmentation dictionaries with tofu-risk included.
        val dict = buildDict(dictDocument, includeTofu = true, DictPath.SEGMENTATION, repeats)
            ?: throw SokkuriException.InvalidConfig("mmseg segmentation dictionary unavailable")
        return MaxMatchSegmentation(dict)
    }

    private fun buildChain(
        specs: List<ConversionDocument>,
        list: String,
        repeats: Map<String, String>,
    ): ConversionChain {
        val conversions = specs.mapIndexedNotNull { index, spec ->
            buildDict(spec.dict, includeTofuRiskDictionaries, DictPath.stage(list, index), repeats)?.let(::Conversion)
        }
        return ConversionChain(conversions)
    }

    private fun buildDict(
        document: DictDocument,
        includeTofu: Boolean,
        path: String,
        repeats: Map<String, String>,
    ): Dictionary? {
        return when (document) {
            is DictDocument.Inline -> buildInline(document, repeats[path])
            is DictDocument.Text -> openFileBacked("text", document.file, document.mayOutputTofu, includeTofu)
            is DictDocument.Ocd -> openFileBacked("ocd", document.file, document.mayOutputTofu, includeTofu)
            is DictDocument.Ocd2 -> openFileBacked("ocd2", document.file, document.mayOutputTofu, includeTofu)
            is DictDocument.Sok -> openFileBacked("sok", document.file, document.mayOutputTofu, includeTofu)
            is DictDocument.Group -> {
                if (document.mayOutputTofu && !includeTofu) return null
                // OpenCC resolves the match policy before filtering children:
                // an unknown policy is an error even for empty groups.
                val policy = parsePolicy(document.matchPolicy)
                val children = document.dicts.mapIndexedNotNull { index, child ->
                    buildDict(child, includeTofu, DictPath.groupMember(path, index), repeats)
                }
                if (children.isEmpty()) null else DictGroup(children, policy)
            }
        }
    }

    private fun openFileBacked(type: String, file: String, mayOutputTofu: Boolean, includeTofu: Boolean): Dictionary? {
        if (mayOutputTofu && !includeTofu) return null
        return dictionaries.open(type, file)
    }

    /** [repeatedKey]: a key the dictionary's entries list more than once in the text, if any. */
    private fun buildInline(document: DictDocument.Inline, repeatedKey: String?): Dictionary {
        // Presence, not value: upstream rejects on HasMember alone, so an
        // explicit `may_output_tofu: false` is an error there too.
        if (document.mayOutputTofu != null) {
            throw SokkuriException.InvalidFormat(
                "inline dictionary does not support may_output_tofu",
            )
        }
        // Decoding keeps only the first occurrence, so the repeat is reported
        // by the structural reading; upstream's LoadInlineDict rejects it.
        if (repeatedKey != null) {
            throw SokkuriException.InvalidConfig("inline dictionary contains duplicate key: $repeatedKey")
        }
        val pairs = document.entries.entries.map { (key, value) ->
            if (key.isEmpty() || value.isEmpty()) {
                throw SokkuriException.InvalidConfig("inline dictionary entries must be non-empty")
            }
            key to listOf(value)
        }
        return SortedListDictionary(pairs)
    }

    private fun parsePolicy(raw: String): GroupMatchPolicy {
        return when (raw) {
            "short_circuit" -> GroupMatchPolicy.SHORT_CIRCUIT
            "union" -> GroupMatchPolicy.UNION
            else -> throw SokkuriException.InvalidConfig("unknown match_policy: $raw")
        }
    }
}
