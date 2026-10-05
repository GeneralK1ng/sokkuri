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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * The two creation entry points and the [Sokkuri.CreateResult] they share.
 *
 * The [Sokkuri.CreateResult.Failure] branch is deliberately absent: reaching
 * it requires the platform resource loader to miss, and every profile's
 * resources are on the test class path by construction. Forcing it would
 * need a loader injection seam that `create` does not have yet
 * (docs/architecture.md §5 #12). The failure path is instead pinned by
 * `SokkuriCreateThrowsContractTest` in jvmTest, which reads the compiled
 * contract.
 */
class CreateResultTest {

    @Test
    fun createResultSucceedsForAPackagedProfile() {
        val result = Sokkuri.createResult(SokkuriConfig.S2T)

        val success = assertIs<Sokkuri.CreateResult.Success>(result)
        assertEquals(SokkuriConfig.S2T, success.sokkuri.config)
        assertEquals("軟件和網絡", success.sokkuri.convert("软件和网络"))
    }

    @Test
    fun createAgreesWithCreateResultForTheSameProfile() {
        val thrown = Sokkuri.create(SokkuriConfig.S2TWP)
        val returned = assertIs<Sokkuri.CreateResult.Success>(
            Sokkuri.createResult(SokkuriConfig.S2TWP),
        ).sokkuri

        assertEquals(thrown.config, returned.config)
        assertEquals(
            thrown.convert("鼠标里面的硅二极管坏了"),
            returned.convert("鼠标里面的硅二极管坏了"),
        )
    }

    @Test
    fun createResultHonoursOptionsLikeCreateDoes() {
        val tofu = SokkuriOptions { includeTofuRiskDictionaries = true }

        // 殢 is one of the three registered t2s tofu divergences: its only
        // route to upstream's output goes through TSCharactersExt, which the
        // default options exclude.
        val byDefault = assertIs<Sokkuri.CreateResult.Success>(
            Sokkuri.createResult(SokkuriConfig.T2S),
        ).sokkuri
        val withTofu = assertIs<Sokkuri.CreateResult.Success>(
            Sokkuri.createResult(SokkuriConfig.T2S, tofu),
        ).sokkuri

        assertNotEquals("𣨼", byDefault.convert("殢"))
        assertEquals("𣨼", withTofu.convert("殢"))
    }
}
