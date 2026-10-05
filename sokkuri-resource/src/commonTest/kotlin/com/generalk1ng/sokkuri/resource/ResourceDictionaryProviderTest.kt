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

@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)

package com.generalk1ng.sokkuri.resource

import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * [DictionaryCache] sharing semantics: providers built over the same loader
 * instance share one decoded dictionary; distinct loader instances never
 * do (a future stateful loader must not receive another loader's entries).
 */
class ResourceDictionaryProviderTest {

    private class FakeLoader(
        private val files: Map<String, ByteArray>,
    ) : ResourceLoader {
        override fun load(path: String): ByteArray? = files[path]
    }

    private val formats: Map<String, DictionaryFormat> =
        mapOf(TextDictionaryFormat.type to TextDictionaryFormat)

    @Test
    fun sameLoaderSharesDecodedDictionaryAcrossProviders() {
        val loader = FakeLoader(mapOf("d.txt" to "干\t幹\n".encodeToByteArray()))
        val first = ResourceDictionaryProvider(loader, formats)
        val second = ResourceDictionaryProvider(loader, formats)

        assertSame(first.open("text", "d.txt"), second.open("text", "d.txt"))
    }

    @Test
    fun distinctLoadersNeverShare() {
        val files = mapOf("d.txt" to "干\t幹\n".encodeToByteArray())
        val first = ResourceDictionaryProvider(FakeLoader(files), formats)
        val second = ResourceDictionaryProvider(FakeLoader(files), formats)

        assertNotSame(first.open("text", "d.txt"), second.open("text", "d.txt"))
    }
}
