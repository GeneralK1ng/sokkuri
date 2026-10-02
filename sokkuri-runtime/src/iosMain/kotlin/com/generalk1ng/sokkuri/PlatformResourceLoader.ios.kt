@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, SokkuriInternalApi::class)

package com.generalk1ng.sokkuri

import com.generalk1ng.sokkuri.resource.ResourceLoader
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.posix.*

/**
 * Reads resources from Apple bundles. Tries the embedding framework first
 * (when consumers link `Sokkuri.framework` with its resource payload), then
 * the main bundle (when resources are copied into the app package). File
 * reads go through POSIX stdio so no Foundation factory-selector bindings
 * are involved.
 *
 * Framework lookup: Kotlin/Native generates the framework's
 * CFBundleIdentifier from the baseName — `com.generalk1ng.sokkuri.Sokkuri`
 * for this module — so both that spelling and the bare namespace are
 * probed. The resources sit in the framework bundle via an explicit Gradle
 * copy (sokkuri-runtime/build.gradle.kts); Kotlin/Native does not place
 * processed resources there by itself.
 *
 * Compose Multiplatform consumers get their Kotlin resources collected into
 * a `compose-resources/` directory of the app bundle (the podspec ships
 * `build/compose/cocoapods/compose-resources` as a folder), so that prefix
 * is probed as well; the resources of plain-KMP consumers are copied to the
 * bundle root and resolve through the first probe.
 */
private const val composeResourcesDirectory = "compose-resources"

internal class BundleResourceLoader internal constructor() : ResourceLoader {

    private val bundles: List<NSBundle> by lazy {
        listOfNotNull(
            NSBundle.bundleWithIdentifier("com.generalk1ng.sokkuri.Sokkuri"),
            NSBundle.bundleWithIdentifier("com.generalk1ng.sokkuri"),
            NSBundle.mainBundle,
        ).distinct()
    }

    override fun load(path: String): ByteArray? {
        for (bundle in bundles) {
            val base = bundle.resourcePath ?: continue
            readFile("$base/$path")?.let { return it }
            readFile("$base/$composeResourcesDirectory/$path")?.let { return it }
        }
        return null
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
}

// Singleton so every Sokkuri.create shares one loader identity and therefore
// one dictionary-cache scope (docs/architecture.md §4.3).
private val defaultLoader: ResourceLoader = BundleResourceLoader()

internal actual fun defaultResourceLoader(): ResourceLoader = defaultLoader
