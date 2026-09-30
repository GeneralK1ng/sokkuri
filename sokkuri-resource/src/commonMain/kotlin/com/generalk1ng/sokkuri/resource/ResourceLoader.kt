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
     */
    public fun load(path: String): ByteArray?
}
