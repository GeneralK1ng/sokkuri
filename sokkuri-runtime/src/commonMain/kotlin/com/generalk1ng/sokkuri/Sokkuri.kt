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

import com.generalk1ng.sokkuri.config.ConfigParser
import com.generalk1ng.sokkuri.engine.Converter
import com.generalk1ng.sokkuri.resource.DefaultDictionaryFormats
import com.generalk1ng.sokkuri.resource.ResourceDictionaryProvider

/**
 * A Chinese conversion engine, behaviorally aligned with OpenCC.
 *
 * Instances are immutable and safe for concurrent use.
 *
 * ```kotlin
 * val sokkuri = Sokkuri.create(SokkuriConfig.S2TWP)
 * sokkuri.convert("鼠标里面的硅二极管坏了") // "滑鼠裡面的矽二極體壞了"
 * ```
 */
@OptIn(SokkuriInternalApi::class)
public class Sokkuri private constructor(
    /** The built-in profile this instance was created from. */
    public val config: SokkuriConfig,
    private val converter: Converter,
) {

    /** Converts [input] according to [config]. */
    public fun convert(input: String): String = converter.convert(input)

    /**
     * Converts [input] and reports the intermediate stages; see [Inspection]
     * for the alignment with OpenCC's inspection mode.
     */
    public fun inspect(input: String): Inspection = converter.inspect(input)

    public companion object {
        /**
         * Creates a converter for [config].
         *
         * The profile's JSON configuration and its dictionaries are loaded
         * through the platform default resource loader (classpath on JVM,
         * assets on Android, framework bundle on iOS).
         *
         * On Android, call [Sokkuri.init] once during application startup
         * before using this factory.
         */
        @OptIn(SokkuriInternalApi::class)
        public fun create(
            config: SokkuriConfig,
            options: SokkuriOptions = SokkuriOptions.DEFAULT,
        ): Sokkuri {
            val loader = defaultResourceLoader()
            val configPath = "config/${config.stem}.json"
            val configJson = loader.load(configPath)?.decodeToString()
                ?: throw SokkuriException.FileNotFound(configPath)
            val dictionaries = ResourceDictionaryProvider(loader, DefaultDictionaryFormats)
            val converter = ConfigParser(dictionaries, options.includeTofuRiskDictionaries).parse(configJson)
            return Sokkuri(config, converter)
        }

        // Future extension points (source-compatible additions):
        //   create(config, options, loader)  — custom resource loading;
        //     bound DictionaryCache's loader registry first (architecture.md
        //     §5 #12, §4.3)
        //   fromConfig(json, ...)            — user-supplied OpenCC configs
    }
}
