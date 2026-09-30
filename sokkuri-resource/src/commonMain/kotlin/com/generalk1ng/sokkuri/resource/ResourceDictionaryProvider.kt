package com.generalk1ng.sokkuri.resource

import com.generalk1ng.sokkuri.SokkuriInternalApi

import com.generalk1ng.sokkuri.SokkuriException
import com.generalk1ng.sokkuri.config.DictionaryProvider
import com.generalk1ng.sokkuri.engine.Dictionary

/**
 * The default [DictionaryProvider]: resolves dictionary files through a
 * [ResourceLoader], decodes them with the matching [DictionaryFormat], and
 * caches decoded instances keyed by `type:file` so repeated
 * [com.generalk1ng.sokkuri.Sokkuri.create] calls do not re-read megabyte
 * dictionaries (OpenCC's `DictCache` equivalent).
 *
 * The cache is process-wide per provider instance; providers created by
 * separate [com.generalk1ng.sokkuri.Sokkuri.create] calls currently do not
 * share it — a shared cache keyed by loader identity is a future
 * optimization, see roadmap.
 */
@SokkuriInternalApi
public class ResourceDictionaryProvider public constructor(
    private val loader: ResourceLoader,
    private val formats: Map<String, DictionaryFormat>,
) : DictionaryProvider {

    private val lock: SokkuriLock = SokkuriLock()
    private val cache: MutableMap<String, Dictionary> = HashMap()

    override fun open(type: String, file: String): Dictionary {
        val key = "$type:$file"
        lock.withLock { cache[key] }?.let { return it }

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

        return lock.withLock {
            // Double-checked: a racing thread may have decoded the same file.
            cache.getOrPut(key) { decoded }
        }
    }
}
