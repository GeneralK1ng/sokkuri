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

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cache bookkeeping both builds rely on to decide what is stale. */
class BuildSupportTest {

    @Test
    fun aStampMatchesOnlyTheValueItRecorded() {
        val dir = Files.createTempDirectory("stamp").toFile()
        try {
            val stamp = Stamp(dir.resolve("nested/build.stamp"))
            assertFalse(stamp.matches("abc"), "a missing stamp never matches")
            stamp.record("abc")
            assertTrue(stamp.matches("abc"))
            assertFalse(stamp.matches("abd"))
            stamp.record("abd")
            assertTrue(stamp.matches("abd"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aPlainFolderHasNoCommit() {
        val dir = Files.createTempDirectory("not-a-clone").toFile()
        try {
            // Even inside a git work tree, a folder without its own .git answers nothing.
            assertNull(cloneCommit(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
