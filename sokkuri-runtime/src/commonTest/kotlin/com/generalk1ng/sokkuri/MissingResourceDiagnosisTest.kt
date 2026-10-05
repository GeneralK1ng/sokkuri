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

import com.generalk1ng.sokkuri.resource.DefaultDictionaryFormats
import com.generalk1ng.sokkuri.resource.ResourceDictionaryProvider
import com.generalk1ng.sokkuri.resource.SokDictionaryFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A missing resource must reach the caller with the loader's own diagnosis
 * (architecture.md §6, I9). These run against the real platform [ResourceLoader]
 * on every test runtime — JVM classpath, Android host, iOS simulator — so each
 * platform actual's wording is exercised, not just a fixture's.
 *
 * The other production throw site, the profile config in
 * [Sokkuri.Companion.createResult], composes the hint identically but is not
 * reachable from a test: every profile's config is packaged, and there is no
 * seam to inject a loader short of handbook #12. It is therefore left to
 * review rather than to a guard.
 */
class MissingResourceDiagnosisTest {

    /**
     * A blank hint would reproduce exactly the bare "not found" this invariant
     * exists to prevent, and the interface cannot enforce non-blankness by type.
     */
    @Test
    fun everyPlatformLoaderPublishesADiagnosis() {
        assertTrue(
            defaultResourceLoader().missingResourceHint().isNotBlank(),
            "the platform resource loader must describe where it looks",
        )
    }

    @Test
    fun aMissingDictionaryCarriesTheLoadersDiagnosis() {
        val loader = defaultResourceLoader()
        val provider = ResourceDictionaryProvider(loader, DefaultDictionaryFormats)

        val failure = assertFailsWith<SokkuriException.FileNotFound> {
            provider.open(SokDictionaryFormat.type, "dictionary/AbsentOnPurpose.sok")
        }

        assertEquals("dictionary/AbsentOnPurpose.sok", failure.path)
        assertTrue(
            failure.message!!.contains(loader.missingResourceHint()),
            "expected the loader's hint in: ${failure.message}",
        )
    }
}
