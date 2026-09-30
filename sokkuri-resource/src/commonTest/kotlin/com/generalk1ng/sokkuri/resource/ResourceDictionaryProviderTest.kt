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
