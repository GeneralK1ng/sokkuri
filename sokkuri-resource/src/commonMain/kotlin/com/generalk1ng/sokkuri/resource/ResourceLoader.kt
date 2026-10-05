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

package com.generalk1ng.sokkuri.resource

import com.generalk1ng.sokkuri.SokkuriInternalApi

/**
 * Platform boundary for reading packaged bytes (config JSON, dictionary
 * binaries). Implementations are provided by the aggregate `sokkuri` module:
 *
 * - JVM: classpath resources (`ClassLoader.getResourceAsStream`);
 * - Android: application assets, with a classpath fallback so host unit
 *   tests work before `Sokkuri.init` runs;
 * - iOS: framework/main bundles.
 *
 * This interface is the only IO abstraction in the resource layer; the
 * config and engine layers stay pure.
 */
@SokkuriInternalApi
public interface ResourceLoader {

    /**
     * Returns the resource at [path] (forward-slash separated, relative to
     * the package root), or `null` when absent.
     *
     * Absence is reported by returning `null`, never by throwing. The loader
     * knows where it looked, not whether the miss is fatal — a caller may
     * still resolve the path elsewhere — so the decision to fail belongs to
     * the caller, which composes the failure from [missingResourceHint].
     */
    public fun load(path: String): ByteArray?

    /**
     * Where [load] looks, plus the platform-specific remedy when it misses,
     * phrased for the developer reading the failure.
     *
     * Used verbatim as the `detail` of
     * [com.generalk1ng.sokkuri.SokkuriException.FileNotFound], so it must
     * never be blank. It lives on the loader because the loader is the only
     * component that knows the search scope; a throw site observes just a
     * path and could not describe what was probed
     * (docs/architecture.md §6, I9).
     */
    public fun missingResourceHint(): String
}
