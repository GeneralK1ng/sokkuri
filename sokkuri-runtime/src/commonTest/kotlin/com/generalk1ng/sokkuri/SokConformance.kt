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

@file:OptIn(SokkuriInternalApi::class)

package com.generalk1ng.sokkuri

import com.generalk1ng.sokkuri.resource.SokDictionary
import com.generalk1ng.sokkuri.resource.SokDictionaryFormat
import kotlin.test.assertEquals

/**
 * The Kotlin half of the conformance contract with the independent Rust
 * reader (`tools/sok-rs`, docs/design/sok-rs.md §5 and §6): the same verdict
 * and the same canonical digest, computed from the runtime decoder, so that
 * the expectation lines `sokcheck expect` writes can be checked line by line.
 */
internal object SokConformance {

    /** What a reader makes of a file, in the terms of an expectation line. */
    sealed interface Verdict {
        data class Accept(val entryCount: Int, val maxKeyLength: Int, val digest: String) : Verdict

        data object Reject : Verdict
    }

    /**
     * The runtime decoder's verdict on [bytes]. Rejecting means throwing
     * [SokkuriException.InvalidFormat]; any other exception escapes, since the
     * decoder must never fail in another way.
     */
    fun judge(bytes: ByteArray): Verdict {
        val dictionary = try {
            SokDictionaryFormat.decode(bytes) as SokDictionary
        } catch (_: SokkuriException.InvalidFormat) {
            return Verdict.Reject
        }
        return Verdict.Accept(dictionary.entryCount, dictionary.maxKeyLength, hex(digest(dictionary)))
    }

    /**
     * One line of an expectation file: the name, then `accept` with the entry
     * count, maxKeyLength and digest, or `reject` with a rule id; fields are
     * tab-separated and trailing fields are ignored, so later phases can
     * append some. The rule id is for people and is not compared.
     */
    fun parseExpectation(line: String): Pair<String, Verdict> {
        val fields = line.split('\t')
        val verdict = when (fields.getOrNull(1)) {
            "accept" -> {
                require(fields.size >= 5) { "an accept line has five fields: $line" }
                Verdict.Accept(fields[2].toInt(), fields[3].toInt(), fields[4])
            }
            "reject" -> Verdict.Reject
            else -> throw IllegalArgumentException("not an expectation line: $line")
        }
        return fields[0] to verdict
    }

    /**
     * The canonical digest: FNV-1a 64 over the entry count, maxKeyLength and,
     * per entry, the stored key and candidate bytes with their lengths, all
     * little-endian (the stream docs/design/sok-rs.md §5 defines).
     */
    fun digest(dictionary: SokDictionary): ULong {
        var hash = FNV_OFFSET_BASIS
        fun write(bytes: ByteArray) {
            for (byte in bytes) hash = (hash xor (byte.toInt() and 0xFF).toULong()) * FNV_PRIME
        }
        fun writeLittleEndian(value: Int, width: Int) = write(ByteArray(width) { (value ushr (8 * it)).toByte() })
        writeLittleEndian(dictionary.entryCount, 4)
        writeLittleEndian(dictionary.maxKeyLength, 4)
        for (index in 0..<dictionary.entryCount) {
            val key = dictionary.rawKey(index)
            writeLittleEndian(key.size, 4)
            write(key)
            val candidates = dictionary.rawCandidates(index)
            writeLittleEndian(candidates.size, 1)
            for (candidate in candidates) {
                writeLittleEndian(candidate.size, 2)
                write(candidate)
            }
        }
        return hash
    }

    fun hex(digest: ULong): String = digest.toString(16).padStart(16, '0')

    /**
     * Looks every entry up the way the runtime does, which must not throw for
     * any accepted file. When [auditClean], the file also meets the invariants
     * retrieval relies on (keys ordered and unique, maxKeyLength exact), so
     * every key must be found again with its own candidates.
     */
    fun exercise(dictionary: SokDictionary, auditClean: Boolean, where: String) {
        for (index in 0..<dictionary.entryCount) {
            val key = dictionary.rawKey(index).decodeToString()
            val exact = dictionary.matchExact(key)
            val prefix = dictionary.matchPrefix(key.toCharArray(), 0, key.length)
            if (auditClean) {
                val candidates = dictionary.rawCandidates(index).map { it.decodeToString() }
                assertEquals(candidates, exact?.candidates, "$where: matchExact of entry $index")
                assertEquals(key.length, prefix?.length, "$where: matchPrefix length of entry $index")
                assertEquals(candidates.first(), prefix?.value, "$where: matchPrefix value of entry $index")
            }
        }
    }

    private const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325uL
    private const val FNV_PRIME: ULong = 0x100000001b3uL
}
