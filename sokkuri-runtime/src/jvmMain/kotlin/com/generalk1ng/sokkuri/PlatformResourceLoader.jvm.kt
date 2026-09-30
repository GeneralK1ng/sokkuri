@file:OptIn(SokkuriInternalApi::class)
package com.generalk1ng.sokkuri

import com.generalk1ng.sokkuri.resource.ResourceLoader

@OptIn(SokkuriInternalApi::class)
internal actual fun defaultResourceLoader(): ResourceLoader = ClasspathResourceLoader()
