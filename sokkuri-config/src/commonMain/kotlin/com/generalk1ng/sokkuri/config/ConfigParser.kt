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
import kotlinx.serialization.decodeFromString

/**
 * Builds an immutable engine [Converter] from an OpenCC configuration JSON
 * document — the port of OpenCC's `Config`/`ConfigInternal`.
 *
 * Semantics mirrored from the C++ implementation:
 *
 * - `normalization` stages form a pre-pass applied to the whole input
 *   before segmentation (`ConfigBasedConverter`);
 * - `segmentation` must be of type `mmseg`; its dictionary is loaded with
 *   tofu-risk dictionaries *included* (OpenCC's `ParseSegmentation` passes
 *   `includeTofuRiskDictionaries = true`);
 * - each `conversion_chain` stage drops out when its dictionary is excluded
 *   by the tofu policy (`ParseConversion` returning null), and an empty
 *   chain degenerates to identity;
 * - groups honor `match_policy`, defaulting to `short_circuit`;
 * - `inline` dictionaries are single-candidate and validated for non-empty
 *   keys and values.
 */
@SokkuriInternalApi
public class ConfigParser public constructor(
    private val dictionaries: DictionaryProvider,
    private val includeTofuRiskDictionaries: Boolean,
) {

    public fun parse(configJson: String): Converter {
        val document = decode(configJson)
        val normalization: ConversionChain? = document.normalization
            .takeIf { it.isNotEmpty() }
            ?.let { buildChain(it) }
        val segmentation: Segmentation? = document.segmentation?.let { buildSegmentation(it) }
        val chain: ConversionChain = buildChain(document.conversionChain)
        return Converter(normalization, segmentation, chain)
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
