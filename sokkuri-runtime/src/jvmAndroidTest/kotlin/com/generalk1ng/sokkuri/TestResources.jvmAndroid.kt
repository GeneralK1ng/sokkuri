package com.generalk1ng.sokkuri

/**
 * Classpath-backed test-resource reader for JVM and Android host unit tests:
 * both run on the JVM with the processed test resources directory on the
 * class path (host unit tests fall back to classpath loading exactly like
 * the main `ClasspathResourceLoader`, see docs/architecture.md).
 */
internal actual fun readTestResource(path: String): ByteArray? {
    val loader = object {}.javaClass.classLoader ?: return null
    return loader.getResourceAsStream(path)?.use { it.readBytes() }
}
