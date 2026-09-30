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

        val format = formats[type]
            ?: throw SokkuriException.Unsupported("unknown dictionary type '$type'")
        val bytes = loader.load(file)
            ?: throw SokkuriException.FileNotFound(file)
        val decoded = try {
            format.decode(bytes)
        } catch (e: SokkuriException) {
            throw e
        } catch (e: Exception) {
            throw SokkuriException.InvalidFormat("$file: ${e.message ?: "decode failed"}")
        }

        return DictionaryCache.putIfAbsent(key, decoded)
    }
}
