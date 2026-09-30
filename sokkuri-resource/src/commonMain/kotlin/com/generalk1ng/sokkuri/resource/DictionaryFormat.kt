package com.generalk1ng.sokkuri.resource

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
        throw com.generalk1ng.sokkuri.SokkuriException.Unsupported("dictionary type '$type': $reason")
    }
}

/** Formats understood by [ResourceDictionaryProvider]. */
public val DefaultDictionaryFormats: Map<String, DictionaryFormat> = mapOf(
    TextDictionaryFormat.type to TextDictionaryFormat,
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
