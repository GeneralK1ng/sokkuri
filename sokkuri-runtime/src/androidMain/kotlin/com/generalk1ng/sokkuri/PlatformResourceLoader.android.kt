@file:OptIn(SokkuriInternalApi::class)
package com.generalk1ng.sokkuri

import android.content.Context
import com.generalk1ng.sokkuri.resource.ResourceLoader
import java.io.IOException

/**
 * Holds the application context for asset-based resource loading. Set once
 * via [Sokkuri.init] before the first [Sokkuri.create] call.
 */
internal object AndroidResourceContext {
    // Written during Application.onCreate, read by converter threads later.
    @Volatile
    internal var appContext: Context? = null
}

internal class AndroidResourceLoader internal constructor() : ResourceLoader {

    override fun load(path: String): ByteArray? {
        val context = AndroidResourceContext.appContext
        if (context == null) {
            // Host unit tests run without an Application and resolve from the
            // classpath. On device, a miss here means Sokkuri.init was never
            // called — say so instead of surfacing a bare not-found.
            return ClasspathResourceLoader().load(path)
                ?: throw SokkuriException.FileNotFound(
                    path,
                    "Android assets unavailable; did you call Sokkuri.init(context)?",
                )
        }
        return try {
            context.assets.open(path).use { stream -> stream.readBytes() }
        } catch (e: IOException) {
            null
        }
    }
}

@OptIn(SokkuriInternalApi::class)
internal actual fun defaultResourceLoader(): ResourceLoader = AndroidResourceLoader()
