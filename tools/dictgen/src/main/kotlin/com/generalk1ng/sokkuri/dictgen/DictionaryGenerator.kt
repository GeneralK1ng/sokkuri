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

import com.generalk1ng.sokkuri.SokkuriException
import com.generalk1ng.sokkuri.SokkuriInternalApi
import com.generalk1ng.sokkuri.config.ConfigDocument
import com.generalk1ng.sokkuri.config.ConfigParser
import com.generalk1ng.sokkuri.config.ConversionDocument
import com.generalk1ng.sokkuri.config.DictDocument
import com.generalk1ng.sokkuri.config.DictionaryProvider
import com.generalk1ng.sokkuri.config.JsonSupport
import com.generalk1ng.sokkuri.resource.SokDictionaryEncoder
import com.generalk1ng.sokkuri.resource.TextDictionaryFormat
import java.io.File

/**
 * How one packaged `.sok` dictionary is obtained from the OpenCC clone —
 * either a direct copy of a checked-in `.txt`, or one of the script-derived
 * lexicons (m1-dictgen-sok §3.4; recipes verified against
 * `OpenCC/data/CMakeLists.txt`).
 */
internal sealed interface DictionaryRecipe {

    /** Packaged file name, e.g. `"STPhrases.sok"`. */
    val outputFile: String

    /** Reads/parses the source entries for this recipe. */
    fun loadEntries(generator: DictionaryGenerator): List<LexiconEntry>

    /** A checked-in `data/dictionary/<name>.txt` compiled as-is. */
    @ConsistentCopyVisibility
    data class Direct internal constructor(private val txtName: String) : DictionaryRecipe {
        override val outputFile: String get() = txtName.removeSuffix(".txt") + ".sok"
        override fun loadEntries(generator: DictionaryGenerator): List<LexiconEntry> =
            generator.parseDictionaryTxt(txtName)
    }

    /** `reverse.py <name>.txt` (with `@reverse-prefer` annotations). */
    @ConsistentCopyVisibility
    data class Reversed internal constructor(private val txtName: String) : DictionaryRecipe {
        override val outputFile: String get() = txtName.removeSuffix(".txt") + "Rev.sok"
        override fun loadEntries(generator: DictionaryGenerator): List<LexiconEntry> =
            DictionaryDerivations.reverse(generator.parseDictionaryTxtWithPreferences(txtName), txtName)
    }

    /** `extract_tofu_risk.py` applied to [txtName] (feeds TSCharactersExt). */
    @ConsistentCopyVisibility
    data class TofuRiskExtract internal constructor(
        private val txtName: String,
        private val outputBase: String,
    ) : DictionaryRecipe {
        override val outputFile: String get() = "$outputBase.sok"
        override fun loadEntries(generator: DictionaryGenerator): List<LexiconEntry> =
            DictionaryDerivations.extractTofuRisk(
                generator.readDictionaryLines(txtName),
                txtName,
            )
    }

    /** `generate_st_phrases_from_regional_phrases.py` over [txtNames]. */
    @ConsistentCopyVisibility
    data class RegionalStPhrases internal constructor(
        private val txtNames: List<String>,
        private val outputBase: String,
    ) : DictionaryRecipe {
        override val outputFile: String get() = "$outputBase.sok"
        override fun loadEntries(generator: DictionaryGenerator): List<LexiconEntry> {
            val inputs = txtNames.map { it to generator.parseDictionaryTxt(it) }
            val text = DictionaryDerivations.generateRegionalStPhrases(
                inputs = inputs,
                convert = generator::convertRegionalKey,
                outputFileName = "$outputBase.txt",
            )
            return parseLexicon(text, "$outputBase.txt (generated)").entries
        }
    }
}

/**
 * Compiles dictionaries from an OpenCC clone into `.sok` bytes.
 *
 * The regional-phrase derivation needs a t2s converter; it is bootstrapped
 * from the clone's own `TS*.txt` files through Sokkuri's config layer
 * (dogfooding — the same pipeline step 0 validated), with
 * [com.generalk1ng.sokkuri.resource.TextDictionaryFormat] as the decoder.
 * `TSCharactersExt.txt` does not exist in the clone (it is itself derived),
 * so the bootstrap provider serves the extracted variant in memory —
 * mirroring upstream, which bootstraps this step with prebuilt `.ocd2`s.
 */
internal class DictionaryGenerator internal constructor(
    private val openccDir: File,
) {

    private val dictionaryDir: File = openccDir.resolve("data/dictionary")
    private val availableTxts: Set<String> =
        dictionaryDir.list()?.filter { it.endsWith(".txt") }?.toSet().orEmpty()

    /** Recipe for a dictionary referenced by the packaged configs. */
    internal fun recipeFor(baseName: String): DictionaryRecipe = when (baseName) {
        "TSCharactersExt" -> DictionaryRecipe.TofuRiskExtract("TSCharacters.txt", baseName)
        "STPhrases_GeneratedFromRegionalPhrases" ->
            DictionaryRecipe.RegionalStPhrases(listOf("HKPhrases.txt", "TWPhrases.txt"), baseName)
        "TWVariantsRev" -> DictionaryRecipe.Reversed("TWVariants.txt")
        "HKVariantsRev" -> DictionaryRecipe.Reversed("HKVariants.txt")
        "JPShinjitaiCharactersRev" -> DictionaryRecipe.Reversed("JPShinjitaiCharacters.txt")
        else -> {
            if ("$baseName.txt" in availableTxts) {
                DictionaryRecipe.Direct("$baseName.txt")
            } else {
                throw DictgenException(
                    "no source recipe for dictionary '$baseName': not a checked-in .txt " +
                        "and not a known derivation (m1-dictgen-sok §3.4)",
                )
            }
        }
    }

    internal fun readDictionaryLines(txtName: String): List<String> =
        dictionaryFile(txtName).readText().split('\n')

    internal fun parseDictionaryTxt(txtName: String): List<LexiconEntry> =
        parseDictionaryTxtWithPreferences(txtName).entries

    internal fun parseDictionaryTxtWithPreferences(txtName: String): ParsedLexicon =
        parseLexicon(dictionaryFile(txtName).readText(), txtName)

    /** Compiles [recipe] into its `.sok` bytes. */
    @OptIn(SokkuriInternalApi::class)
    internal fun compile(recipe: DictionaryRecipe): Pair<String, ByteArray> {
        val entries = recipe.loadEntries(this)
        val pairs = entries.map { it.key to it.values }
        return recipe.outputFile to SokDictionaryEncoder.encode(pairs)
    }

    private fun dictionaryFile(txtName: String): File {
        val file = dictionaryDir.resolve(txtName)
        if (!file.isFile) {
            throw DictgenException("missing dictionary source: ${file.path}")
        }
        return file
    }

    // --- t2s bootstrap for the regional-phrase derivation ---

    @OptIn(SokkuriInternalApi::class)
    private val regionalKeyConverter: (String) -> String by lazy {
        val provider = BootstrapProvider()
        val parser = ConfigParser(provider, includeTofuRiskDictionaries = true)
        val convert = parser.parse(bootstrapT2sJson())::convert
        convert
    }

    internal fun convertRegionalKey(key: String): String = regionalKeyConverter(key)

    @OptIn(SokkuriInternalApi::class)
    private fun bootstrapT2sJson(): String {
        val document = ConfigDocument(
            name = "t2s (dictgen bootstrap)",
            normalization = listOf(
                ConversionDocument(DictDocument.Text("CJK_Compatibility_Ideographs.txt")),
            ),
            segmentation = null,
            conversionChain = listOf(
                ConversionDocument(
                    DictDocument.Group(
                        matchPolicy = "short_circuit",
                        dicts = listOf(
                            DictDocument.Text("TSPhrases.txt"),
                            DictDocument.Text("TSCharactersExt.txt", mayOutputTofu = true),
                            DictDocument.Text("TSCharacters.txt"),
                        ),
                    ),
                ),
            ),
        )
        return JsonSupport.opencc.encodeToString(ConfigDocument.serializer(), document)
    }

    /** Serves `text` dictionaries from the clone, deriving TSCharactersExt on demand. */
    @OptIn(SokkuriInternalApi::class)
    private inner class BootstrapProvider : DictionaryProvider {
        override fun open(type: String, file: String) =
            when (type) {
                "text" -> TextDictionaryFormat.decode(dictionaryBytes(file))
                else -> throw SokkuriException.Unsupported("bootstrap provider only supports text, not '$type'")
            }

        private fun dictionaryBytes(file: String): ByteArray {
            val direct = dictionaryDir.resolve(file)
            if (direct.isFile) return direct.readBytes()
            if (file == "TSCharactersExt.txt") {
                val extracted = DictionaryDerivations.extractTofuRisk(
                    readDictionaryLines("TSCharacters.txt"),
                    "TSCharacters.txt",
                )
                return dumpLexicon(extracted).encodeToByteArray()
            }
            throw SokkuriException.FileNotFound(
                file,
                "not in the OpenCC clone's dictionary directory " +
                        "(${dictionaryDir.path}) — the clone must be present and at " +
                        "the revision the packaged configs expect",
            )
        }
    }
}
