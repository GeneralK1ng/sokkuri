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

package com.generalk1ng.sokkuri

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the `@Throws` list on [Sokkuri.create].
 *
 * Kotlin/Native prunes any type that no exported signature mentions, so an
 * exception subtype missing from that list is invisible to Swift callers
 * even though a thrown instance still crosses the bridge as `NSError`.
 * Nothing in the Kotlin build fails in that case — the list is a plain
 * annotation — which makes "added a subtype, forgot the list" a silent
 * regression. This test turns it into a build failure.
 *
 * JVM-only because it reads the compiled contract: `@Throws` has BINARY
 * retention (invisible to Kotlin reflection) but does emit a JVM `throws`
 * clause, and JVM nested-class metadata enumerates the sealed subtypes
 * without pulling in kotlin-reflect.
 */
class SokkuriCreateThrowsContractTest {

    @Test
    fun createDeclaresEverySokkuriExceptionSubtype() {
        val declared = Sokkuri.Companion::class.java
            .getMethod("create", SokkuriConfig::class.java, SokkuriOptions::class.java)
            .exceptionTypes
            .toSet()

        val subtypes = SokkuriException::class.java
            .declaredClasses
            .filter { SokkuriException::class.java.isAssignableFrom(it) }
            .toSet()

        // A subtype added to the sealed hierarchy without a matching entry
        // in create's @Throws shows up here as a missing declaration.
        val missing = subtypes - declared
        assertTrue(
            missing.isEmpty(),
            "Sokkuri.create's @Throws omits ${missing.map { it.simpleName }}; " +
                "Swift cannot cast Sokkuri.CreateResult.Failure.error to them.",
        )
    }

    @Test
    fun createResultIsNotExportedAsThrowing() {
        // createResult is the non-throwing path: a throws clause here would
        // force `try` on every Swift call site for no reason.
        val declared = Sokkuri.Companion::class.java
            .getMethod(
                "createResult",
                SokkuriConfig::class.java,
                SokkuriOptions::class.java,
            )
            .exceptionTypes

        assertTrue(
            declared.isEmpty(),
            "Sokkuri.createResult must not declare checked failures; found " +
                declared.map { it.simpleName },
        )
    }
}
