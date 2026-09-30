@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)

package com.generalk1ng.sokkuri.engine

/**
 * Runs the [DictionaryContractSuite] against [SortedListDictionary], the
 * sorted-array reference backend (OpenCC `TextDict` port).
 */
class SortedListDictionaryContractTest : DictionaryContractSuite() {

    override fun createDictionary(entries: List<Pair<String, List<String>>>): Dictionary =
        SortedListDictionary(entries)
}
