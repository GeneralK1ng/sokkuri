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
 * A minimal mutual-exclusion primitive for shared caches. Kotlin has no
 * common `synchronized`; each platform actual wraps its native mechanism
 * (monitor on JVM/Android, `NSLock` on Darwin).
 *
 * Lives in the resource layer as the platform primitive guarding the
 * process-wide [DictionaryCache]. (Not in `sokkuri-api`: that module hosts
 * the stable public surface, not internal platform machinery.)
 */
@SokkuriInternalApi
public expect class SokkuriLock() {
    public fun <T> withLock(block: () -> T): T
}
