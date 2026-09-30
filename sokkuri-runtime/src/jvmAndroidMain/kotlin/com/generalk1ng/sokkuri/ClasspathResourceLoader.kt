@file:OptIn(SokkuriInternalApi::class)
package com.generalk1ng.sokkuri

import com.generalk1ng.sokkuri.resource.ResourceLoader

/**
 * Reads resources from the class loader (JAR entries on the JVM; also the
 * fallback for Android host unit tests, where no `Context` exists).
 */
internal class ClasspathResourceLoader internal constructor() : ResourceLoader {

    override fun load(path: String): ByteArray? {
        val normalized = path.trimStart('/')
        val loader = javaClass.classLoader
            ?: Thread.currentThread().contextClassLoader
            ?: return null
        return loader.getResourceAsStream(normalized)?.use { stream -> stream.readBytes() }
    }
}
