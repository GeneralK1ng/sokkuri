package com.generalk1ng.sokkuri.resource

import com.generalk1ng.sokkuri.SokkuriInternalApi

@SokkuriInternalApi
public actual class SokkuriLock {
    private val monitor: Any = Any()

    public actual fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}
