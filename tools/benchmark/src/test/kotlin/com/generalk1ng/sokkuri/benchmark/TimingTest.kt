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

package com.generalk1ng.sokkuri.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The timing methodology: warmup sizing, and the pure summarization
 * math with exact expected percentiles — no wall-clock numbers are
 * asserted anywhere (machine-dependent by definition).
 */
class TimingTest {

    @Test
    fun warmupIsCappedByThe200FloorAndScalesAboveIt() {
        assertEquals(200, Timing.warmupIterations(100))
        assertEquals(200, Timing.warmupIterations(2_000))
        assertEquals(500, Timing.warmupIterations(5_000))
        assertEquals(2_000, Timing.warmupIterations(20_000))
    }

    @Test
    fun summarizeComputesExactNearestRankPercentiles() {
        // 100 samples of 1..100 microseconds (in nanos): median = 50µs,
        // p90 = 90µs (nearest-rank), min = 1µs.
        val samples = LongArray(100) { (it + 1) * 1_000L }
        val result = Timing.summarize(samples, iterations = 100)
        assertEquals(100, result.iterations)
        assertEquals(1.0, result.minUs)
        assertEquals(50.0, result.medianUs)
        assertEquals(90.0, result.p90Us)
    }

    @Test
    fun summarizeNeverExceedsTheMaxSampleAtP90() {
        val samples = longArrayOf(1_000, 2_000, 3_000, 4_000, 100_000_000)
        val result = Timing.summarize(samples, iterations = 5)
        assertEquals(1.0, result.minUs)
        assertTrue(result.medianUs <= result.p90Us)
        assertTrue(result.p90Us <= 100_000.0)
    }

    @Test
    fun measureCollectsExactlyTheRequestedIterations() {
        var calls = 0
        val result = Timing.measure(iterations = 10) { calls += 1 }
        // 10 timed + warmup(200) untimed calls.
        assertEquals(210, calls)
        assertEquals(10, result.iterations)
    }
}
