@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.generalk1ng.sokkuri

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.posix.*

/**
 * Bundle-backed test-resource reader for Apple test executables. The Gradle
 * wiring copies processed test resources beside the test binary, and a bare
 * test executable's `NSBundle.mainBundle` resolves to its containing
 * directory — so a plain relative read through POSIX stdio (mirroring the
 * main `BundleResourceLoader`, no Foundation factory bindings) finds them.
 */
internal actual fun readTestResource(path: String): ByteArray? {
    val base = NSBundle.mainBundle.resourcePath ?: return null
    return readFile("$base/$path")
}

private fun readFile(fullPath: String): ByteArray? {
    val file = fopen(fullPath, "rb") ?: return null
    try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val size = ftell(file)
        if (size <= 0) return null
        rewind(file)
        if (fseek(file, 0, SEEK_SET) != 0) return null
        val bytes = ByteArray(size.toInt())
        val read = bytes.usePinned { pinned ->
            fread(pinned.addressOf(0), 1u, size.toULong(), file)
        }
        return if (read == size.toULong()) bytes else null
    } finally {
        fclose(file)
    }
}
