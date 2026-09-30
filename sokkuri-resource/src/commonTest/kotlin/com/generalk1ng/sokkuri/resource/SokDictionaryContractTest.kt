@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)

package com.generalk1ng.sokkuri.resource

import com.generalk1ng.sokkuri.engine.Dictionary
import com.generalk1ng.sokkuri.engine.DictionaryContractSuite

/**
 * Runs the engine's [DictionaryContractSuite] against `.sok`: entries are
 * encoded with [SokDictionaryEncoder] and decoded through the registered
 * [SokDictionaryFormat], so the binary backend is judged by exactly the
 * contract the sorted-array reference satisfies (m1-dictgen-sok §2.4).
 */
class SokDictionaryContractTest : DictionaryContractSuite() {

    override fun createDictionary(entries: List<Pair<String, List<String>>>): Dictionary =
        SokDictionaryFormat.decode(SokDictionaryEncoder.encode(entries))
}
