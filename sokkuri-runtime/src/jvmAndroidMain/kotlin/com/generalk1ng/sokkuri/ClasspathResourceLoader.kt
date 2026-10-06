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

import com.generalk1ng.sokkuri.resource.ResourceLoader
import java.io.IOException

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
        return try {
            loader.getResourceAsStream(normalized)?.use { stream -> stream.readBytes() }
        } catch (_: IOException) {
            // A truncated or corrupt archive entry fails here rather than
            // being absent. Report it as missing: the loader contract (I9)
            // is that resource failures reach the caller as `null`, never as
            // a throw. A raw IOException would escape Sokkuri.createResult,
            // which converts only SokkuriException, and reach the developer
            // as an untyped JVM error with no diagnostic at all.
            null
        } catch (_: SecurityException) {
            // Denied by a SecurityManager; same reasoning.
            null
        }
    }

    override fun missingResourceHint(): String =
        "not on the classpath: the configs and dictionaries ship inside the " +
                "sokkuri-runtime artifact, so check that it is a runtime dependency " +
                "and that packaging did not filter its resources out"
}
