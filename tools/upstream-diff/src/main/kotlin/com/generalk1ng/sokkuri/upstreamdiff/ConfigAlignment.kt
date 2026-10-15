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

@file:OptIn(SokkuriInternalApi::class)

package com.generalk1ng.sokkuri.upstreamdiff

import com.generalk1ng.sokkuri.SokkuriException
import com.generalk1ng.sokkuri.SokkuriInternalApi
import com.generalk1ng.sokkuri.config.ConfigParser
import com.generalk1ng.sokkuri.resource.DefaultDictionaryFormats
import com.generalk1ng.sokkuri.resource.ResourceDictionaryProvider
import com.generalk1ng.sokkuri.resource.ResourceLoader
import kotlinx.serialization.Serializable
import java.io.File

/** What one implementation did with one config. */
internal sealed interface ConfigOutcome {

    /** Loaded, and converted every probe; [outputs] in probe order. */
    data class Accepted(val outputs: List<String>) : ConfigOutcome

    /**
     * Failed in [phase] with a failure of [category]. [detail] names the
     * exception and carries its message, for the report only: messages are
     * worded differently on purpose and never compared.
     */
    data class Rejected(val phase: Phase, val category: Category, val detail: String) : ConfigOutcome
}

/** When a config failed: while loading, or while converting a probe. */
internal enum class Phase { LOAD, CONVERT }

/**
 * Failure kinds at the granularity both sides can express. Upstream throws
 * `InvalidFormat` for config and dictionary content alike, so Sokkuri's
 * finer split between `InvalidConfig` and `InvalidFormat` collapses into
 * [INVALID]; [UNSUPPORTED] exists only on Sokkuri's side, for the features
 * it deliberately does not port.
 */
internal enum class Category { MISSING_RESOURCE, INVALID, UNSUPPORTED, OTHER }

/** Whether two outcomes count as the same behavior. */
internal fun ConfigOutcome.agreesWith(other: ConfigOutcome): Boolean = when (this) {
    is ConfigOutcome.Accepted -> other is ConfigOutcome.Accepted && outputs == other.outputs
    is ConfigOutcome.Rejected -> other is ConfigOutcome.Rejected && phase == other.phase && category == other.category
}

/** One line of the harness's `config` subcommand, as serialized there. */
@Serializable
internal data class HarnessConfigAnswer(
    val status: String,
    val outputs: List<String>? = null,
    val phase: String? = null,
    val exception: String? = null,
    val message: String? = null,
) {

    fun toOutcome(): ConfigOutcome = when (status) {
        "accepted" -> ConfigOutcome.Accepted(checkNotNull(outputs) { "accepted answer without outputs" })
        "rejected" -> {
            val name = checkNotNull(exception) { "rejected answer without exception" }
            ConfigOutcome.Rejected(
                phase = when (phase) {
                    "load" -> Phase.LOAD
                    "convert" -> Phase.CONVERT
                    else -> error("unknown phase '$phase'")
                },
                category = upstreamCategory(name),
                detail = "$name: $message",
            )
        }
        else -> error("unknown status '$status'")
    }

    companion object {
        /** Maps upstream's exception class names (`Exception.hpp`) onto [Category]. */
        fun upstreamCategory(exception: String): Category = when (exception) {
            "FileNotFound" -> Category.MISSING_RESOURCE
            "InvalidFormat", "InvalidTextDictionary" -> Category.INVALID
            else -> Category.OTHER
        }
    }
}

/** Maps Sokkuri's exception taxonomy onto [Category]. */
internal fun SokkuriException.category(): Category = when (this) {
    is SokkuriException.FileNotFound -> Category.MISSING_RESOURCE
    is SokkuriException.InvalidConfig, is SokkuriException.InvalidFormat -> Category.INVALID
    is SokkuriException.Unsupported -> Category.UNSUPPORTED
}

/**
 * Sokkuri's side of the config comparison: [ConfigParser] over the fixture
 * dictionaries, the same parser `Sokkuri.create` runs. The parser is
 * reached directly because a consumer cannot hand Sokkuri an arbitrary
 * config yet (decision D12).
 *
 * One instance serves a whole run: the process-wide dictionary cache is
 * keyed by loader identity and never evicts, so a loader per case would
 * accumulate in it.
 */
internal class SokkuriConfigLoader(fixtureDir: File) {

    private val dictionaries = ResourceDictionaryProvider(DirectoryLoader(fixtureDir), DefaultDictionaryFormats)

    fun outcome(configJson: String, includeTofu: Boolean, probes: List<String>): ConfigOutcome {
        val converter = try {
            ConfigParser(dictionaries, includeTofu).parse(configJson)
        } catch (e: RuntimeException) {
            return rejected(Phase.LOAD, e)
        }
        return try {
            ConfigOutcome.Accepted(probes.map(converter::convert))
        } catch (e: RuntimeException) {
            rejected(Phase.CONVERT, e)
        }
    }

    /**
     * Anything but a [SokkuriException] breaks Sokkuri's error contract; it
     * is reported as [Category.OTHER] so it shows up as a mismatch instead
     * of ending the run.
     */
    private fun rejected(phase: Phase, e: RuntimeException): ConfigOutcome.Rejected = ConfigOutcome.Rejected(
        phase = phase,
        category = (e as? SokkuriException)?.category() ?: Category.OTHER,
        detail = "${e::class.simpleName}: ${e.message}",
    )

    /** Resolves resource paths against one directory, as upstream resolves files against its search path. */
    private class DirectoryLoader(private val root: File) : ResourceLoader {
        override fun load(path: String): ByteArray? = root.resolve(path).takeIf { it.isFile }?.readBytes()

        override fun missingResourceHint(): String = "looked under ${root.absolutePath}"
    }
}
