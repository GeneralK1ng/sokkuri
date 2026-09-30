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
