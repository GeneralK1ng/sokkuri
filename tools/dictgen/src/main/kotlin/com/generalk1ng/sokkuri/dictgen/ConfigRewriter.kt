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

import com.generalk1ng.sokkuri.SokkuriInternalApi
import com.generalk1ng.sokkuri.config.ConfigDocument
import com.generalk1ng.sokkuri.config.DictDocument
import com.generalk1ng.sokkuri.config.JsonSupport
import kotlinx.serialization.json.Json

/**
 * Rewrites one upstream OpenCC configuration into Sokkuri's packaged form:
 * parses the JSONC source with the config layer's own
 * model ([ConfigDocument]) — so anything Sokkuri cannot parse fails here,
 * at generation time — then rewrites every file-backed dictionary node
 * (`ocd`/`ocd2` → `sok` with a `.sok` file name) while preserving group
 * structure, `match_policy`, `may_output_tofu`, `segmentation`, and inline
 * dictionaries unchanged.
 *
 * Output is strict JSON: comment-free, fields in the model's declaration
 * order, default-valued fields spelled out (so `match_policy` and
 * `may_output_tofu` survive round trips verbatim), 2-space indentation,
 * UTF-8, single trailing newline.
 */
internal object ConfigRewriter {

    private val strict = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** Rewrites one upstream JSONC config text into strict packaged JSON. */
    @OptIn(SokkuriInternalApi::class)
    internal fun rewrite(jsonc: String): String {
        val document = JsonSupport.opencc
            .decodeFromString(ConfigDocument.serializer(), JsonSupport.stripComments(jsonc))
        val rewritten = document.copy(
            normalization = document.normalization.map { it.copy(dict = rewriteDict(it.dict)) },
            segmentation = document.segmentation?.let { segmentation ->
                segmentation.copy(dict = segmentation.dict?.let(::rewriteDict))
            },
            conversionChain = document.conversionChain.map { it.copy(dict = rewriteDict(it.dict)) },
        )
        return strict.encodeToString(ConfigDocument.serializer(), rewritten) + "\n"
    }

    @OptIn(SokkuriInternalApi::class)
    private fun rewriteDict(dict: DictDocument): DictDocument = when (dict) {
        is DictDocument.Group -> dict.copy(dicts = dict.dicts.map(::rewriteDict))
        // Packaged layout: dictionaries live under dictionary/ on the
        // resource classpath (the pilot-established convention); upstream
        // configs reference them bare relative to OpenCC's dict path.
        is DictDocument.Ocd -> dict.toSokPackaged()
        is DictDocument.Ocd2 -> dict.toSokPackaged()
        // Already-packaged nodes are identity, making rewriting idempotent.
        is DictDocument.Sok -> dict
        is DictDocument.Text -> dict
        is DictDocument.Inline -> dict
    }

    @OptIn(SokkuriInternalApi::class)
    private fun DictDocument.Ocd.toSokPackaged() = toSokPackaged(file.removeSuffix(".ocd"), mayOutputTofu)

    @OptIn(SokkuriInternalApi::class)
    private fun DictDocument.Ocd2.toSokPackaged() = toSokPackaged(file.removeSuffix(".ocd2"), mayOutputTofu)

    @OptIn(SokkuriInternalApi::class)
    private fun toSokPackaged(baseName: String, mayOutputTofu: Boolean) =
        DictDocument.Sok("dictionary/$baseName.sok", mayOutputTofu)
}
