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

/**
 * Marks APIs of Sokkuri's internal layers (`sokkuri-engine`, `sokkuri-config`,
 * `sokkuri-resource`) that are technically public for cross-module wiring
 * but are **not** part of the stable public surface.
 *
 * The `sokkuri-runtime` artifact is the only supported entry point.
 * Layer APIs may change in any release without notice; depending on them
 * directly (outside this project's own modules) opts you into breakage.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This is an internal layer API of Sokkuri and is not part of the stable public surface.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
)
public annotation class SokkuriInternalApi
