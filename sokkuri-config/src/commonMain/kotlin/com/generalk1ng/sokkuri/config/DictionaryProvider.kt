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

package com.generalk1ng.sokkuri.config

import com.generalk1ng.sokkuri.SokkuriInternalApi

import com.generalk1ng.sokkuri.engine.Dictionary

/**
 * The seam between the config layer and the resource layer: config parsing
 * stays free of IO by resolving file-backed dictionaries through this
 * provider (the port of OpenCC's `ResourceProvider` + dict loading).
 *
 * Implementations own format detection, caching, and error translation.
 */
@SokkuriInternalApi
public fun interface DictionaryProvider {
    /**
     * Loads and decodes the dictionary referenced by [type] (one of `text`,
     * `ocd`, `ocd2`, or the port's own formats) and [file] (the path exactly
     * as written in the configuration).
     *
     * @throws com.generalk1ng.sokkuri.SokkuriException.FileNotFound when the
     * resource does not exist.
     * @throws com.generalk1ng.sokkuri.SokkuriException.Unsupported when the
     * dictionary type is not a format this build can read.
     * @throws com.generalk1ng.sokkuri.SokkuriException.InvalidFormat when the
     * bytes violate the format.
     */
    public fun open(type: String, file: String): Dictionary
}
