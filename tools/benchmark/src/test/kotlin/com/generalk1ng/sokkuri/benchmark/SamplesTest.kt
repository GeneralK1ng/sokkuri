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
 * The sample set: `medium` leads every profile's list and `long` follows
 * it (both are cross-profile comparable probes), specials attach only to
 * their tagged profile, and the phrase-stress and long samples carry their
 * downshifted default iteration counts.
 */
class SamplesTest {

    @Test
    fun mediumAndLongLeadEveryProfileAndSpecialsAttachOnlyToTheirTag() {
        for (profile in SokkuriConfig.entries) {
            val samples = Samples.forProfile(profile)
            assertEquals(Samples.MEDIUM, samples.first(), "medium must lead for ${profile.stem}")
            when (profile) {
                SokkuriConfig.S2T -> assertEquals(listOf("medium", "long", "short", "phrases"), samples.map { it.id })
                SokkuriConfig.S2TWP -> assertEquals(listOf("medium", "long", "twp"), samples.map { it.id })
                SokkuriConfig.S2HK -> assertEquals(listOf("medium", "long", "hk"), samples.map { it.id })
                else -> assertEquals(listOf("medium", "long"), samples.map { it.id })
            }
        }
    }

    @Test
    fun phraseStressAndLongSamplesAreDownshiftedAndEverythingElseUsesTheDefault() {
        assertEquals(Sample.PHRASE_ITERATIONS, Samples.PHRASES.defaultIterations)
        assertEquals(Sample.LONG_ITERATIONS, Samples.LONG.defaultIterations)
        assertEquals(Sample.DEFAULT_ITERATIONS, Samples.MEDIUM.defaultIterations)
    }

    @Test
    fun sampleTextsAreThePinnedOnes() {
        // Lengths in UTF-16 code units: a stray edit to a shared sample file
        // changes what every recorded benchmark measured.
        val expected = mapOf("medium" to 48, "long" to 823, "short" to 8, "phrases" to 219, "twp" to 5, "hk" to 13)
        for ((id, length) in expected) {
            val text = sampleText(id)
            assertEquals(length, text.length, "length of sample $id")
            assertTrue('\r' !in text, "sample $id must not contain carriage returns")
        }
        assertEquals(
            "鼠标里面的硅二极管坏了，需要更换。软件开发和硬件维护都需要严谨的态度。" +
                "自干五扇风耳朵，铺眉搧眼。",
            Samples.MEDIUM.text,
        )
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
