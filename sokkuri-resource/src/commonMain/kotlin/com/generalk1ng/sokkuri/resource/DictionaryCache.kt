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

import com.generalk1ng.sokkuri.SokkuriInternalApi
import com.generalk1ng.sokkuri.engine.Dictionary

/**
 * The process-wide dictionary cache backing every
 * [ResourceDictionaryProvider].
 *
 * A deliberate divergence from OpenCC's `DictCache` (registered deviation,
 * docs/architecture.md §7): upstream caches process-wide `weak_ptr`s keyed
 * by file mtime+size, the semantics of a desktop tool reading mutable
 * files. Sokkuri's dictionaries are packaged, immutable resources, so:
 *
 * - entries are held by strong reference and never invalidated;
 * - the key is `<loader-scope>:<type>:<file>`;
 * - the loader scope is the *identity* of the loader instance (assigned on
 *   first sight): stateless platform default loaders are singletons, so all
 *   `Sokkuri.create` calls on a platform share one scope and therefore one
 *   decoded dictionary; distinct loader instances never share.
 *
 * KMP common has no unified weak reference, which makes a weak cache
 * infeasible to port faithfully; the strong cache is also exactly what
 * mobile consumers want (several converters share dictionary memory).
 */
@OptIn(SokkuriInternalApi::class)
internal object DictionaryCache {

    private val lock = SokkuriLock()
    private val cache = HashMap<String, Dictionary>()

    // Loader identity registry: reference-equality scan, entries never
    // removed. This list is what keeps a scope stable, so it owns the
    // loader — the bound is "loaders ever seen", not alive ones
    // (docs/architecture.md §4.3).
    private val loaderScopes = ArrayList<Pair<ResourceLoader, Int>>()
    private var nextScope: Int = 0

    fun scopeOf(loader: ResourceLoader): Int = lock.withLock {
        for ((existing, scope) in loaderScopes) {
            if (existing === loader) return@withLock scope
        }
        val scope = nextScope++
        loaderScopes.add(loader to scope)
        scope
    }

    fun get(key: String): Dictionary? = lock.withLock { cache[key] }

    /**
     * Inserts [value] unless a racing thread decoded the same key first;
     * returns the instance that won the cache (callers discard their copy).
     */
    fun putIfAbsent(key: String, value: Dictionary): Dictionary = lock.withLock {
        val existing = cache[key]
        if (existing != null) {
            existing
        } else {
            cache[key] = value
            value
        }
    }
}
