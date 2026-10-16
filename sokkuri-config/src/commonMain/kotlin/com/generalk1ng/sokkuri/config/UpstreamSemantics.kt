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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Serializers for the members OpenCC's `Config.cpp` reads more loosely than a
 * typed model would. Upstream walks a rapidjson DOM and looks at each member
 * only as far as it needs; where that is looser than kotlinx-serialization's
 * typed decoding, these serializers reproduce upstream's reading, so a config
 * upstream accepts is not rejected here over a member upstream ignores.
 */

/**
 * The informational `name`: upstream reads it only when it is a string
 * (`doc.HasMember("name") && doc["name"].IsString()`) and otherwise ignores
 * it, so any other JSON value decodes to `null` instead of failing the
 * config. Encodes as a plain string.
 */
internal object NameSerializer : KSerializer<String?> {

    // String's own descriptor rather than a named PrimitiveSerialDescriptor:
    // naming a primitive makes kotlinx-serialization check the name against
    // its table of built-in serializers, which loads some seventy classes the
    // first time, about 7 ms of every JVM cold start. JSON never reads a
    // primitive's serial name.
    override val descriptor: SerialDescriptor = String.serializer().descriptor

    override fun deserialize(decoder: Decoder): String? {
        if (decoder !is JsonDecoder) return decoder.decodeString()
        return (decoder.decodeJsonElement() as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}

/**
 * A list of conversions (`conversion_chain`, `normalization`) as upstream's
 * `ParseConversionChain` reads it: the member must be an array, but array
 * members that are not objects are skipped (`if (doc.IsObject()) … else {}`)
 * instead of failing the config. Encodes as a plain list.
 */
@OptIn(SokkuriInternalApi::class)
internal abstract class ConversionListSerializer(private val member: String) : KSerializer<List<ConversionDocument>> {

    private val delegate = ListSerializer(ConversionDocument.serializer())

    override val descriptor: SerialDescriptor get() = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<ConversionDocument> {
        if (decoder !is JsonDecoder) return delegate.deserialize(decoder)
        val array = decoder.decodeJsonElement() as? JsonArray
            ?: throw SerializationException("Property must be an array: $member")
        return array.filterIsInstance<JsonObject>()
            .map { decoder.json.decodeFromJsonElement(ConversionDocument.serializer(), it) }
    }

    override fun serialize(encoder: Encoder, value: List<ConversionDocument>) {
        delegate.serialize(encoder, value)
    }
}

internal object ConversionChainSerializer : ConversionListSerializer("conversion_chain")

internal object NormalizationSerializer : ConversionListSerializer("normalization")
