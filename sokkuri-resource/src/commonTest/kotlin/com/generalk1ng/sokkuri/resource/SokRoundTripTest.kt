@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)

package com.generalk1ng.sokkuri.resource

import com.generalk1ng.sokkuri.engine.Dictionary
import com.generalk1ng.sokkuri.engine.SortedListDictionary
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Round-trip and determinism tests for the `.sok` encoder/decoder pair
 * (m1-dictgen-sok §2.4). Retrieval equivalence with the sorted-array
 * reference is pinned entry-by-entry here and behaviorally by
 * [SokDictionaryContractTest].
 */
class SokRoundTripTest {

    private val sample: List<Pair<String, List<String>>> = listOf(
        "a" to listOf("A"),
        "ab" to listOf("AB1", "AB2"),
        "abc" to listOf("ABC"),
        "万" to listOf("萬", "万"),
        "⿰日月" to listOf("明"),
        "𠀀" to listOf("x"),
    )

    private fun roundTrip(entries: List<Pair<String, List<String>>>): Dictionary =
        SokDictionaryFormat.decode(SokDictionaryEncoder.encode(entries))

    @Test
    fun encodingIsByteForByteDeterministic() {
        val first = SokDictionaryEncoder.encode(sample)
        val second = SokDictionaryEncoder.encode(sample)
        assertContentEquals(first, second)
    }

    @Test
    fun encodingIsIndependentOfInputOrder() {
        // The encoder sorts by code-point order; shuffled input must give
        // the identical bytes (dictgen `--check` relies on this).
        val shuffled = sample.shuffled()
        assertContentEquals(SokDictionaryEncoder.encode(sample), SokDictionaryEncoder.encode(shuffled))
    }

    @Test
    fun encodedBytesMatchTheSpecifiedLayout() {
        // Hand-computed golden for entries [("a",["A"]),("b",["B1","B2"])]:
        // pins the byte-level spec independently of SokFormatLayout, so the
        // encoder and its mirror constants cannot drift together.
        val golden = byteArrayOf(
            // header: magic, version=1, flags=0, entryCount=2,
            // maxKeyLength=1, keyBlobBytes=2, valueBlobBytes=11, reserved=0
            0x53, 0x4F, 0x4B, 0x31,
            0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x02, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x02, 0x00, 0x00, 0x00,
            0x0B, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            // keyOffsets [0, 1, 2]
            0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00,
            0x02, 0x00, 0x00, 0x00,
            // valueOffsets [0, 3, 11]
            0x00, 0x00, 0x00, 0x00,
            0x03, 0x00, 0x00, 0x00,
            0x0B, 0x00, 0x00, 0x00,
            // valueCounts [1, 2]
            0x01, 0x02,
            // keyBlob "a" "b"
            0x61, 0x62,
            // valueBlob: [len 1]["A"] [len 2]["B1"] [len 2]["B2"]
            0x01, 0x00, 0x41,
            0x02, 0x00, 0x42, 0x31,
            0x02, 0x00, 0x42, 0x32,
        )
        assertContentEquals(golden, SokDictionaryEncoder.encode(listOf("a" to listOf("A"), "b" to listOf("B1", "B2"))))
    }

    @Test
    fun decodedDictionaryPreservesEveryEntryAndCandidate() {
        val decoded = roundTrip(sample)
        assertEquals(3, decoded.maxKeyLength)
        for ((key, candidates) in sample) {
            assertEquals(candidates, decoded.matchExact(key)?.candidates, "candidates of '$key'")
            assertEquals(candidates.first(), decoded.matchPrefix(key.toCharArray(), 0, key.length)?.value)
        }
    }

    @Test
    fun decodedDictionaryAgreesWithSortedListDictionaryProbeByProbe() {
        val sok = roundTrip(sample)
        val reference = SortedListDictionary(sample)
        val probes = listOf(
            "a", "ab", "abc", "abcd", "万", "萬", "⿰日月光", "𠀀b", "xyz", "",
        )
        for (probe in probes) {
            val chars = probe.toCharArray()
            val fromSok = sok.matchPrefix(chars, 0, chars.size)
            val fromReference = reference.matchPrefix(chars, 0, chars.size)
            assertEquals(fromReference?.length, fromSok?.length, "length for '$probe'")
            assertEquals(fromReference?.value, fromSok?.value, "value for '$probe'")
            for (index in chars.indices) {
                val atSok = sok.matchPrefix(chars, index, chars.size)
                val atReference = reference.matchPrefix(chars, index, chars.size)
                assertEquals(atReference?.length, atSok?.length, "length for '$probe'@$index")
                assertEquals(atReference?.value, atSok?.value, "value for '$probe'@$index")
            }
        }
    }

    @Test
    fun emptyLexiconRoundTrips() {
        val decoded = roundTrip(emptyList())
        assertEquals(0, decoded.maxKeyLength)
        assertNull(decoded.matchExact("a"))
        assertNull(decoded.matchPrefix("a".toCharArray(), 0, 1))
    }
}
