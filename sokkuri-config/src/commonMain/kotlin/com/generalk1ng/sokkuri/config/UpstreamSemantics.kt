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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
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

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.generalk1ng.sokkuri.config.Name", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        if (decoder !is JsonDecoder) return decoder.decodeString()
        return (decoder.decodeJsonElement() as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}
