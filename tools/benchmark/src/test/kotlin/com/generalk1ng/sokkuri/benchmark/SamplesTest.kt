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

import com.generalk1ng.sokkuri.SokkuriConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sample set: `medium` leads every profile's list (it is the
 * cross-profile comparable probe), specials attach only to their tagged
 * profile, and the phrase-stress sample carries its downshifted default
 * iteration count.
 */
class SamplesTest {

    @Test
    fun mediumLeadsEveryProfileAndSpecialsAttachOnlyToTheirTag() {
        for (profile in SokkuriConfig.entries) {
            val samples = Samples.forProfile(profile)
            assertEquals(Samples.MEDIUM, samples.first(), "medium must lead for ${profile.stem}")
            when (profile) {
                SokkuriConfig.S2T -> assertEquals(listOf("medium", "short", "phrases"), samples.map { it.id })
                SokkuriConfig.S2TWP -> assertEquals(listOf("medium", "twp"), samples.map { it.id })
                SokkuriConfig.S2HK -> assertEquals(listOf("medium", "hk"), samples.map { it.id })
                else -> assertEquals(listOf("medium"), samples.map { it.id })
            }
        }
    }

    @Test
    fun phraseStressSampleIsDownshiftedAndEverythingElseUsesTheDefault() {
        assertEquals(Sample.PHRASE_ITERATIONS, Samples.PHRASES.defaultIterations)
        assertEquals(Sample.DEFAULT_ITERATIONS, Samples.MEDIUM.defaultIterations)
    }

    @Test
    fun everySampleTextIsNonBlank() {
        for (profile in SokkuriConfig.entries) {
            for ((id, text) in Samples.forProfile(profile)) {
                assertTrue(text.isNotBlank(), "blank sample text: $id")
            }
        }
    }
}
