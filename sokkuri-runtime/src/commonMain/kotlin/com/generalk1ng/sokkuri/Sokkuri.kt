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
 * Create one with [Companion.create] (throws on failure) or
 * [Companion.createResult] (returns a [CreateResult]; the path that stays
 * typed across the Objective-C bridge).
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

    /**
     * The outcome of [Companion.createResult]: a usable converter, or the
     * [SokkuriException] that prevented one.
     *
     * This exists because Kotlin exceptions do not survive the Objective-C
     * bridge as typed values. A thrown exception reaches Swift as a plain
     * `NSError` (domain `KotlinException`), so `catch let error as
     * SokkuriException` compiles but never matches — only `(error as
     * NSError).kotlinException` yields the Kotlin object. Returning a sealed
     * result gives Swift a typed, non-throwing path instead
     * (docs/architecture.md §6, I4).
     */
    public sealed class CreateResult {

        /** A converter for the requested profile. */
        public class Success public constructor(
            public val sokkuri: Sokkuri,
        ) : CreateResult()

        /**
         * Creation failed for an environmental reason — a missing or corrupt
         * resource, or an unusable configuration. [error] carries the
         * concrete [SokkuriException] subtype for callers that branch on it.
         */
        public class Failure public constructor(
            public val error: SokkuriException,
        ) : CreateResult()
    }

    public companion object {
        /**
         * Creates a converter for [config], throwing on failure.
         *
         * The profile's JSON configuration and its dictionaries are loaded
         * through the platform default resource loader (classpath on JVM,
         * assets on Android, framework bundle on iOS).
         *
         * On Android, call [Sokkuri.init] once during application startup
         * before using this factory.
         *
         * The `@Throws` list must name every [SokkuriException] subtype. The
         * Objective-C export prunes any type no exported signature mentions,
         * so a subtype omitted here is invisible to Swift even though a
         * thrown instance still crosses the bridge. Omitting one degrades
         * Swift's casts on [CreateResult.Failure] to the base type; it does
         * not restore the process termination that the annotation itself
         * prevents.
         */
        @Throws(
            SokkuriException.FileNotFound::class,
            SokkuriException.InvalidFormat::class,
            SokkuriException.InvalidConfig::class,
            SokkuriException.Unsupported::class,
        )
        public fun create(
            config: SokkuriConfig,
            options: SokkuriOptions = SokkuriOptions.DEFAULT,
        ): Sokkuri = when (val result = createResult(config, options)) {
            is CreateResult.Success -> result.sokkuri
            is CreateResult.Failure -> throw result.error
        }

        /**
         * Creates a converter for [config] without throwing; failures are
         * reported as [CreateResult.Failure].
         *
         * Declared to never throw, so it is exported to Swift without a
         * `throws` clause and needs no `NSError` unwrapping.
         *
         * Only [SokkuriException] is converted. A `Throwable` outside the
         * pipeline's taxonomy is a defect rather than an environmental
         * condition, and propagates instead of masquerading as a recoverable
         * outcome.
         */
        @OptIn(SokkuriInternalApi::class)
        public fun createResult(
            config: SokkuriConfig,
            options: SokkuriOptions = SokkuriOptions.DEFAULT,
        ): CreateResult {
            val loader = defaultResourceLoader()
            val configPath = "config/${config.stem}.json"
            return try {
                val configJson = loader.load(configPath)?.decodeToString()
                    ?: throw SokkuriException.FileNotFound(configPath)
                val dictionaries = ResourceDictionaryProvider(loader, DefaultDictionaryFormats)
                val converter = ConfigParser(dictionaries, options.includeTofuRiskDictionaries)
                    .parse(configJson)
                CreateResult.Success(Sokkuri(config, converter))
            } catch (e: SokkuriException) {
                CreateResult.Failure(e)
            }
        }

        // Future extension points (source-compatible additions):
        //   create(config, options, loader)  — custom resource loading;
        //     bound DictionaryCache's loader registry first (architecture.md
        //     §5 #12, §4.3)
        //   fromConfig(json, ...)            — user-supplied OpenCC configs
    }
}
