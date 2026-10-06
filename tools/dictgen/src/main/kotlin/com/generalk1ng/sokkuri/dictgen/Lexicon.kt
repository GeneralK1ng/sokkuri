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

package com.generalk1ng.sokkuri.dictgen

/** One lexicon line: a key with its ordered conversion candidates. */
@ConsistentCopyVisibility
internal data class LexiconEntry internal constructor(
    val key: String,
    val values: List<String>,
)

/**
 * Result of parsing an OpenCC text dictionary: the entries plus any
 * `# @reverse-prefer:` annotations (only meaningful to
 * [DictionaryDerivations.reverse]).
 */
@ConsistentCopyVisibility
internal data class ParsedLexicon internal constructor(
    val entries: List<LexiconEntry>,
    val reversePreferences: Map<String, String>,
)

/**
 * Parses the OpenCC text dictionary line format (`key<TAB>v1 v2`), with the
 * `common.BaseDict.iter` semantics of OpenCC's data scripts: `#` comment
 * lines and blank lines are skipped; values are separated by single spaces
 * (empty fragments from runs of spaces are dropped, matching the runtime
 * `TextDictionaryFormat`). Errors name [fileName] and the 1-based line
 * number.
 */
internal fun parseLexicon(text: String, fileName: String): ParsedLexicon {
    val entries = ArrayList<LexiconEntry>()
    val preferences = LinkedHashMap<String, String>()
    val lines = text.split('\n')
    for (index in lines.indices) {
        val line = lines[index].trimEnd('\r')
        if (line.isEmpty()) continue
        if (line.startsWith(REVERSE_PREFERENCE_PREFIX)) {
            val fields = line.removePrefix(REVERSE_PREFERENCE_PREFIX)
                .split(' ')
                .filter { it.isNotEmpty() }
            if (fields.size !in 1..2) {
                throw DictgenException(
                    "Invalid reverse preference at line ${index + 1} in $fileName: $line",
                )
            }
            preferences[fields[0]] = fields[fields.size - 1]
            continue
        }
        if (line.startsWith("#")) continue
        entries.add(parseEntryLine(line, fileName, index + 1))
    }
    return ParsedLexicon(entries, preferences)
}

/**
 * Parses one `key<TAB>values` line, or throws naming [fileName] and
 * [lineNumber]. Shared by [parseLexicon] and the `@tofu-risk` extractor.
 */
internal fun parseEntryLine(line: String, fileName: String, lineNumber: Int): LexiconEntry {
    val tab = line.indexOf('\t')
    if (tab < 0) {
        throw DictgenException("$fileName: line $lineNumber: missing tab delimiter")
    }
    val key = line.substring(0, tab)
    val values = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
    if (key.isEmpty()) {
        throw DictgenException("$fileName: line $lineNumber: empty key")
    }
    if (values.isEmpty()) {
        throw DictgenException("$fileName: line $lineNumber: entry '$key' has no values")
    }
    return LexiconEntry(key, values)
}

/**
 * Renders entries in the canonical text-dictionary line format
 * (`key<TAB>v1 v2\n`) — the format the upstream data scripts write.
 */
internal fun dumpLexicon(entries: List<LexiconEntry>): String =
    buildString {
        for ((key, values) in entries) {
            append(key).append('\t').append(values.joinToString(" ")).append('\n')
        }
    }

internal const val REVERSE_PREFERENCE_PREFIX: String = "# @reverse-prefer:"
internal const val TOFU_RISK_PREFIX: String = "# @tofu-risk:"
