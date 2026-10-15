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

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Subcommand and flag parsing, including the bare-flags form CI has always used. */
class InvocationTest {

    private val cwd = File("/repo")

    @Test
    fun bareFlagsRunTheDiff() {
        val invocation = parseInvocation(listOf("--tofu"), cwd)
        assertEquals(Command.DIFF, invocation.command)
        assertTrue(invocation.options.includeTofu)
        assertEquals(File(cwd, "OpenCC"), invocation.options.openccDir)
        assertEquals(File(cwd, "build/upstream-opencc"), invocation.options.buildDir)
    }

    @Test
    fun namedSubcommandsTakeTheirOwnFlags() {
        assertEquals(Command.DIFF, parseInvocation(emptyList(), cwd).command)
        assertEquals(Command.DIFF, parseInvocation(listOf("diff", "--inputs", "7"), cwd).command)
        val configs = parseInvocation(listOf("configs", "--rebuild"), cwd)
        assertEquals(Command.CONFIGS, configs.command)
        assertTrue(configs.options.rebuild)
    }

    @Test
    fun flagsOfAnotherSubcommandAreRejected() {
        assertFailsWith<IllegalArgumentException> { parseInvocation(listOf("configs", "--tofu"), cwd) }
        assertFailsWith<IllegalArgumentException> { parseInvocation(listOf("--inputs"), cwd) }
        assertFailsWith<IllegalArgumentException> { parseInvocation(listOf("--inputs", "many"), cwd) }
        assertFailsWith<IllegalArgumentException> { parseInvocation(listOf("--frobnicate"), cwd) }
    }
}
