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

package com.generalk1ng.sokkuri.resource

import com.generalk1ng.sokkuri.SokkuriException
import com.generalk1ng.sokkuri.SokkuriInternalApi
import com.generalk1ng.sokkuri.config.DictionaryProvider
import com.generalk1ng.sokkuri.engine.Dictionary

/**
 * The default [DictionaryProvider]: resolves dictionary files through a
 * [ResourceLoader], decodes them with the matching [DictionaryFormat], and
 * stores decoded instances in the process-wide [DictionaryCache] keyed by
 * loader scope + `type:file`, so repeated
 * [com.generalk1ng.sokkuri.Sokkuri.create] calls do not re-read megabyte
 * dictionaries (OpenCC's `DictCache` equivalent — see [DictionaryCache]
 * for the deliberate divergence in invalidation semantics).
 */
@SokkuriInternalApi
public class ResourceDictionaryProvider public constructor(
    private val loader: ResourceLoader,
    private val formats: Map<String, DictionaryFormat>,
) : DictionaryProvider {

    private val scope: Int = DictionaryCache.scopeOf(loader)

    override fun open(type: String, file: String): Dictionary {
        val key = "$scope:$type:$file"
        DictionaryCache.get(key)?.let { return it }

        // Config-driven callers cannot land on this fallback: ConfigParser
        // resolves `dict.type` against the sealed DictDocument hierarchy and
        // reports an unknown one as InvalidConfig before `open` runs (I10,
        // D11), while `ocd`/`ocd2` are registered as UnsupportedDictionaryFormat
        // and fail inside `decode`. Only a direct call naming a type outside
        // the supplied registry reaches here.
        val format = formats[type]
            ?: throw SokkuriException.Unsupported("unknown dictionary type '$type'")
        // The loader is the only component that knows where it looked, so
        // the diagnosis travels with the miss (architecture.md §6, I9).
        val bytes = loader.load(file)
            ?: throw SokkuriException.FileNotFound(file, loader.missingResourceHint())
        val decoded = try {
            format.decode(bytes)
        } catch (e: SokkuriException.InvalidFormat) {
            // Decode errors are field-level (".sok: flags must be 0"); the
            // file path only exists at this layer, so compose both here
            // (the message must carry both).
            throw SokkuriException.InvalidFormat("$file: ${e.detail}")
        } catch (e: SokkuriException) {
            throw e
        } catch (e: Exception) {
            throw SokkuriException.InvalidFormat("$file: ${e.message ?: "decode failed"}")
        }

        return DictionaryCache.putIfAbsent(key, decoded)
    }
}
