package com.generalk1ng.sokkuri.resource

import com.generalk1ng.sokkuri.SokkuriInternalApi

/**
 * A minimal mutual-exclusion primitive for shared caches. Kotlin has no
 * common `synchronized`; each platform actual wraps its native mechanism
 * (monitor on JVM/Android, `NSLock` on Darwin).
 *
 * Lives in the resource layer as the platform primitive backing
 * [ResourceDictionaryProvider]'s cache — the only consumer. (Not in
 * `sokkuri-api`: that module hosts the stable public surface, not internal
 * platform machinery.)
 */
@SokkuriInternalApi
public expect class SokkuriLock() {
    public fun <T> withLock(block: () -> T): T
}
