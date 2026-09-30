package com.generalk1ng.sokkuri.benchmark

import com.generalk1ng.sokkuri.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The §4 sample set: `medium` leads every profile's list (it is the
 * cross-profile comparable probe), specials attach only to their tagged
 * profile, and the phrase-stress sample carries its downshifted default
 * iteration count.
 */
class SamplesTest {

    @Test
    fun mediumLeadsEveryProfileAndSpecialsAttachOnlyToTheirTag() {
        for (profile in Config.entries) {
            val samples = Samples.forProfile(profile)
            assertEquals(Samples.MEDIUM, samples.first(), "medium must lead for ${profile.stem}")
            when (profile) {
                Config.S2T -> assertEquals(listOf("medium", "short", "phrases"), samples.map { it.id })
                Config.S2TWP -> assertEquals(listOf("medium", "twp"), samples.map { it.id })
                Config.S2HK -> assertEquals(listOf("medium", "hk"), samples.map { it.id })
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
        for (profile in Config.entries) {
            for ((id, text) in Samples.forProfile(profile)) {
                assertTrue(text.isNotBlank(), "blank sample text: $id")
            }
        }
    }
}
