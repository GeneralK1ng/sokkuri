@file:OptIn(SokkuriInternalApi::class)
package com.generalk1ng.sokkuri

import com.generalk1ng.sokkuri.resource.ResourceLoader

// Singleton so every Sokkuri.create shares one loader identity and therefore
// one dictionary-cache scope (docs/architecture.md §4.3).
private val defaultLoader: ResourceLoader = ClasspathResourceLoader()

@OptIn(SokkuriInternalApi::class)
internal actual fun defaultResourceLoader(): ResourceLoader = defaultLoader
