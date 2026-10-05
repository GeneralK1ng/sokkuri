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

/**
 * Kotlin ports of OpenCC's dictionary derivation scripts
 * (`data/scripts/common.py::reverse_items`, `extract_tofu_risk.py`,
 * `generate_st_phrases_from_regional_phrases.py`). Semantics follow the
 * Python sources line by line; the byte-diff tests against the actual
 * script outputs pin the alignment (m1-dictgen-sok §3.4.3).
 */
internal object DictionaryDerivations {

    /**
     * Port of `reverse_items`: builds the value→keys multimap (preserving
     * first-seen order), applies the `@reverse-prefer` annotations by
     * moving the preferred original key to the front, and sorts the result
     * by key in code-point order.
     *
     * @throws DictgenException when a preference references a missing
     * mapping, mirroring the script's `ValueError`.
     */
    internal fun reverse(lexicon: ParsedLexicon, fileName: String): List<LexiconEntry> {
        val reversed = LinkedHashMap<String, ArrayList<String>>()
        for ((key, values) in lexicon.entries) {
            for (value in values) {
                reversed.getOrPut(value) { ArrayList() }.add(key)
            }
        }
        for ((key, preferred) in lexicon.reversePreferences) {
            val values = reversed[key]
            if (values == null || preferred !in values) {
                throw DictgenException(
                    "Reverse preference $key -> $preferred has no matching mapping ($fileName)",
                )
            }
            // insert(0, pop(index(preferred))): remove first occurrence,
            // prepend it.
            values.remove(preferred)
            values.add(0, preferred)
        }
        return reversed.map { (key, values) -> LexiconEntry(key, values) }
            .sortedWith { a, b -> CodePointOrder.compare(a.key, b.key) }
    }

    /**
     * Port of `extract_tofu_risk`: for every `# @tofu-risk:` annotation,
     * takes the following line as an extension mapping, drops the first
     * value when it equals the key (a self-mapping), and fails when no
     * extension value remains. Entry order is preserved as-is.
     */
    internal fun extractTofuRisk(lines: List<String>, fileName: String): List<LexiconEntry> {
        val extracted = ArrayList<LexiconEntry>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index].trimEnd('\r')
            if (!line.startsWith(TOFU_RISK_PREFIX)) {
                index += 1
                continue
            }
            val mappingLine = lines.getOrNull(index + 1)
                ?: throw DictgenException(
                    "Missing mapping after $TOFU_RISK_PREFIX at line ${index + 1} in $fileName",
                )
            val mapping = mappingLine.trimEnd('\r')
            if (mapping.isBlank() || mapping.startsWith("#")) {
                throw DictgenException(
                    "Expected mapping after $TOFU_RISK_PREFIX at line ${index + 1} in $fileName",
                )
            }
            val entry = parseEntryLine(mapping, fileName, index + 2)
            val values = if (entry.values.first() == entry.key) entry.values.drop(1) else entry.values
            if (values.isEmpty()) {
                throw DictgenException(
                    "Expected extension mapping after $TOFU_RISK_PREFIX at line ${index + 1} in $fileName",
                )
            }
            extracted.add(LexiconEntry(entry.key, values))
            index += 2
        }
        return extracted
    }

    /**
     * Port of the standalone mode of
     * `generate_st_phrases_from_regional_phrases.py`: converts each input
     * key through t2s ([convert], already tofu-inclusive), drops
     * conversions shorter than 3 code points, and maps each surviving
     * simplified key to the first regional key projecting to it.
     *
     * @param inputs regional dictionaries as (file name, entries) pairs, in
     * script `--input` order; only the names' basenames enter the header
     * @param convert the t2s conversion function (keys converted one by one)
     * @param outputFileName basename written into the header comment
     * @return the complete file text, header included, byte-aligned with
     * the script's output
     * @throws DictgenException listing every conflicting projection
     * (distinct regional sources for one simplified key), like the script
     */
    internal fun generateRegionalStPhrases(
        inputs: List<Pair<String, List<LexiconEntry>>>,
        convert: (String) -> String,
        outputFileName: String,
    ): String {
        val collisions = LinkedHashMap<String, ArrayList<String>>()
        for ((_, entries) in inputs) {
            for ((key) in entries) {
                val converted = convert(key)
                // Short regional keys split longer Simplified words before
                // STPhrases can match them (script comment).
                if (CodePointOrder.codePointLength(converted) < 3) continue
                collisions.getOrPut(converted) { ArrayList() }.add(key)
            }
        }
        val conflicts = collisions.filterValues { it.toSet().size > 1 }
        if (conflicts.isNotEmpty()) {
            val listing = conflicts.keys
                .sortedWith { a, b -> CodePointOrder.compare(a, b) }
                .joinToString("\n") { "  $it: ${conflicts.getValue(it).joinToString(" ")}" }
            throw DictgenException("Conflicting regional phrase simplified projections:\n$listing")
        }
        val generated = collisions.map { (key, originals) -> LexiconEntry(key, listOf(originals[0])) }
            .sortedWith { a, b -> CodePointOrder.compare(a.key, b.key) }
        return buildString {
            append("# Open Chinese Convert (OpenCC) Dictionary\n")
            append("# File: ").append(outputFileName).append('\n')
            append("# Format: key\tvalue(s) (values separated by spaces)\n")
            append("# License: Apache-2.0 (see LICENSE)\n")
            append("# Source: generated from ")
                .append(inputs.joinToString(", ") { it.first })
                .append(" keys via t2s.json\n")
            append("# Used in configs: s2hkp.json, s2twp.json\n")
            append("#\n")
            append("# This generated ST phrase dictionary preserves Simplified-input spans\n")
            append("# before applying regional phrase vocabulary.\n")
            append('\n')
            for ((key, values) in generated) {
                append(key).append('\t').append(values.joinToString(" ")).append('\n')
            }
        }
    }
}
