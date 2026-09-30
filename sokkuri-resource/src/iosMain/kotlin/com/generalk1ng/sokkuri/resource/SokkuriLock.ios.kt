package com.generalk1ng.sokkuri.resource

import com.generalk1ng.sokkuri.SokkuriInternalApi

import platform.Foundation.NSLock

@SokkuriInternalApi
public actual class SokkuriLock {
    private val lock: NSLock = NSLock()

    public actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
