@file:OptIn(SokkuriInternalApi::class)
package com.generalk1ng.sokkuri

import com.generalk1ng.sokkuri.resource.ResourceLoader

/**
 * The platform default loader used by [Sokkuri.create], resolved against
 * the aggregate module's platform source sets.
 */
@OptIn(SokkuriInternalApi::class)
internal expect fun defaultResourceLoader(): ResourceLoader
