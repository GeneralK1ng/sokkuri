package com.generalk1ng.sokkuri

import android.content.Context

/**
 * Initializes asset-based dictionary loading on Android.
 *
 * Call once during application startup (e.g. from `Application.onCreate`)
 * before the first [Sokkuri.create]. Until this runs, the resource loader
 * falls back to the classpath, which keeps host unit tests working but
 * cannot see packaged assets on device.
 */
public fun Sokkuri.Companion.init(context: Context): Unit {
    AndroidResourceContext.appContext = context.applicationContext
}
