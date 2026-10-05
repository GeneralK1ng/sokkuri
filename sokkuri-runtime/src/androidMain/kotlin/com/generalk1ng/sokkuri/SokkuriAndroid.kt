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
