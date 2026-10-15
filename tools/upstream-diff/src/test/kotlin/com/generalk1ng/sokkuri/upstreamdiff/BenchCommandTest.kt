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

package com.generalk1ng.sokkuri.upstreamdiff

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The reference timing's inputs and output shape, which must line up with tools:benchmark's. */
class BenchCommandTest {

    @Test
    fun readsTheSharedSampleTexts() {
        // The same pins as tools:benchmark's SamplesTest: both tools read one set of files.
        val expected = mapOf("medium" to 48, "long" to 823, "short" to 8, "phrases" to 219, "twp" to 5, "hk" to 13)
        for ((id, length) in expected) {
            assertEquals(length, sampleTextOrNull(id)?.length, "length of sample $id")
        }
        assertNull(sampleTextOrNull("absent"))
        assertEquals(expected.keys, BENCH_ITERATIONS.keys, "every sample has a default iteration count")
    }

    @Test
    fun decodesTheHarnessTiming() {
        val timing = Json.decodeFromString(
            UpstreamTiming.serializer(),
            """{"iterations":2000,"medianUs":17.584,"p90Us":19.583,"minUs":16.541}""",
        )
        assertEquals(UpstreamTiming(2000, 17.584, 19.583, 16.541), timing)
    }

    @Test
    fun linesMatchTheBenchmarkToolShape() {
        assertEquals(
            "[bench] kind=upstream profile=s2t sample=long iterations=2000 medianUs=17.58 p90Us=19.58 minUs=16.54",
            benchLine("s2t", "long", UpstreamTiming(2000, 17.584, 19.583, 16.541)),
        )
    }
}
