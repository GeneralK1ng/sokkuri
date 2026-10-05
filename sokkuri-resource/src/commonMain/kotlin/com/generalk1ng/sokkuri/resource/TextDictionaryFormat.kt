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

package com.generalk1ng.sokkuri.resource

import com.generalk1ng.sokkuri.SokkuriInternalApi

import com.generalk1ng.sokkuri.SokkuriException
import com.generalk1ng.sokkuri.engine.Dictionary
import com.generalk1ng.sokkuri.engine.SortedListDictionary

/**
 * Decoder for OpenCC's text dictionary format (`data/dictionary/<name>.txt`), the
 * port of `Lexicon::ParseLexiconFromBuffer`:
 *
 * - an optional UTF-8 BOM is skipped;
 * - `#` lines and blank lines are ignored;
 * - each entry is `key<TAB>candidate[ candidate...]` — the first candidate
 *   is the default output;
 * - keys must be unique; unsorted input is sorted by code-point order.
 *
 * Retained as the `text` config type (useful for user-supplied dictionaries)
 * and as the reference decoder for validating the production binary format.
 */
@SokkuriInternalApi
public object TextDictionaryFormat : DictionaryFormat {

    override val type: String = "text"

    override fun decode(bytes: ByteArray): Dictionary {
        var text = bytes.decodeToString()
        if (text.startsWith('﻿')) text = text.substring(1)

        val pairs = ArrayList<Pair<String, List<String>>>()
        var lineNumber = 0
        for (rawLine in text.split('\n')) {
            lineNumber += 1
            val line = if (rawLine.endsWith('\r')) rawLine.dropLast(1) else rawLine
            if (line.isEmpty() || line[0] == '#') continue

            val tab = line.indexOf('\t')
            if (tab < 0) {
                throw SokkuriException.InvalidFormat("line $lineNumber: missing tab delimiter")
            }
            val key = line.substring(0, tab)
            if (key.isEmpty()) {
                throw SokkuriException.InvalidFormat("line $lineNumber: empty key")
            }
            val candidates = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
            if (candidates.isEmpty()) {
                throw SokkuriException.InvalidFormat("line $lineNumber: entry '$key' has no values")
            }
            pairs.add(key to candidates)
        }

        return try {
            SortedListDictionary(pairs)
        } catch (e: SokkuriException) {
            throw e
        } catch (e: Exception) {
            throw SokkuriException.InvalidFormat(e.message ?: "invalid text dictionary")
        }
    }
}
