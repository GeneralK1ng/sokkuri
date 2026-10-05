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
        val context =
            AndroidResourceContext.appContext ?: // Host unit tests run without an Application and resolve from the
            // classpath. On device, a miss here means Sokkuri.init was never
            // called — say so instead of surfacing a bare not-found.
            return ClasspathResourceLoader().load(path)
                ?: throw SokkuriException.FileNotFound(
                    path,
                    "Android assets unavailable; did you call Sokkuri.init(context)?",
                )
        return try {
            context.assets.open(path).use { stream -> stream.readBytes() }
        } catch (e: IOException) {
            // KMP commonMain resources land in the library jar on Android,
            // not in assets; fall back to the class loader so packaged
            // resources resolve on device as well as in host tests.
            ClasspathResourceLoader().load(path)
        }
    }
}

// Singleton so every Sokkuri.create shares one loader identity and therefore
// one dictionary-cache scope (docs/architecture.md §4.3); the loader itself
// is stateless and reads the static AndroidResourceContext.
private val defaultLoader: ResourceLoader = AndroidResourceLoader()

@OptIn(SokkuriInternalApi::class)
internal actual fun defaultResourceLoader(): ResourceLoader = defaultLoader
