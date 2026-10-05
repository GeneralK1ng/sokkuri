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
        val document = decode(configJson)
        val segmentation: Segmentation? = document.segmentation?.let(::buildSegmentation)
        val chain: ConversionChain = buildChain(document.conversionChain)
        val main = Converter.SingleStage(segmentation, chain)
        val normalization: Converter? = document.normalization
            .takeIf { it.isNotEmpty() }
            ?.let { Converter.SingleStage(segmentation = null, chain = buildChain(it)) }
        return if (normalization != null) {
            Converter.Normalizing(normalization, main)
        } else {
            main
        }
    }

    private fun decode(configJson: String): ConfigDocument {
        val cleaned = JsonSupport.stripComments(configJson)
        return try {
            JsonSupport.opencc.decodeFromString<ConfigDocument>(cleaned)
        } catch (e: SerializationException) {
            throw SokkuriException.InvalidConfig(e.message ?: "malformed JSON")
        } catch (e: IllegalArgumentException) {
            throw SokkuriException.InvalidConfig(e.message ?: "malformed JSON")
        }
    }

    private fun buildSegmentation(document: SegmentationDocument): Segmentation {
        if (document.type != "mmseg") {
            throw SokkuriException.Unsupported("segmentation type '${document.type}'")
        }
        val dictDocument = document.dict
            ?: throw SokkuriException.InvalidConfig("mmseg segmentation requires a dict")
        // OpenCC loads segmentation dictionaries with tofu-risk included.
        val dict = buildDict(dictDocument, includeTofu = true)
            ?: throw SokkuriException.InvalidConfig("mmseg segmentation dictionary unavailable")
        return MaxMatchSegmentation(dict)
    }

    private fun buildChain(specs: List<ConversionDocument>): ConversionChain {
        val conversions = specs.mapNotNull { spec ->
            buildDict(spec.dict, includeTofu = includeTofuRiskDictionaries)?.let(::Conversion)
        }
        return ConversionChain(conversions)
    }

    private fun buildDict(document: DictDocument, includeTofu: Boolean): Dictionary? {
        return when (document) {
            is DictDocument.Inline -> buildInline(document)
            is DictDocument.Text -> openFileBacked("text", document.file, document.mayOutputTofu, includeTofu)
            is DictDocument.Ocd -> openFileBacked("ocd", document.file, document.mayOutputTofu, includeTofu)
            is DictDocument.Ocd2 -> openFileBacked("ocd2", document.file, document.mayOutputTofu, includeTofu)
            is DictDocument.Sok -> openFileBacked("sok", document.file, document.mayOutputTofu, includeTofu)
            is DictDocument.Group -> {
                if (document.mayOutputTofu && !includeTofu) return null
                // OpenCC resolves the match policy before filtering children:
                // an unknown policy is an error even for empty groups.
                val policy = parsePolicy(document.matchPolicy)
                val children = document.dicts.mapNotNull { buildDict(it, includeTofu) }
                if (children.isEmpty()) null else DictGroup(children, policy)
            }
        }
    }

    private fun openFileBacked(type: String, file: String, mayOutputTofu: Boolean, includeTofu: Boolean): Dictionary? {
        if (mayOutputTofu && !includeTofu) return null
        return dictionaries.open(type, file)
    }

    private fun buildInline(document: DictDocument.Inline): Dictionary {
        if (document.mayOutputTofu) {
            throw SokkuriException.InvalidFormat(
                "inline dictionary does not support may_output_tofu",
            )
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
