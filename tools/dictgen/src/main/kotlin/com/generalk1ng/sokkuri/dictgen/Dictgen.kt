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

@file:OptIn(com.generalk1ng.sokkuri.SokkuriInternalApi::class)

package com.generalk1ng.sokkuri.dictgen

import com.generalk1ng.sokkuri.SokkuriConfig
import com.generalk1ng.sokkuri.config.ConfigDocument
import com.generalk1ng.sokkuri.config.DictDocument
import com.generalk1ng.sokkuri.config.JsonSupport
import java.io.File
import kotlin.system.exitProcess

/**
 * `tools:dictgen` — compiles the OpenCC clone's dictionaries and configs
 * into Sokkuri's packaged resources (`.sok` dictionaries + rewritten
 * configs), see docs/milestones/m1-dictgen-sok.md. This file is the CLI
 * boundary; all work happens in [run], which returns a process exit code.
 */
@ConsistentCopyVisibility
internal data class DictgenOptions internal constructor(
    val openccDir: File,
    val outputDir: File,
    val check: Boolean,
)

/**
 * Parses CLI arguments: `--opencc-dir <dir>` (default `<cwd>/OpenCC`),
 * `--output-dir <dir>` (default `<cwd>/sokkuri-runtime/src/commonMain/resources`),
 * `--check` (compare against the output dir instead of writing), `--help`.
 */
internal fun parseArgs(args: Array<String>, cwd: File): DictgenOptions {
    var openccDir = File(cwd, "OpenCC")
    var outputDir = File(cwd, "sokkuri-runtime/src/commonMain/resources")
    var check = false
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--opencc-dir" -> {
                openccDir = File(requireValue(args, ++index, "--opencc-dir"))
            }
            "--output-dir" -> {
                outputDir = File(requireValue(args, ++index, "--output-dir"))
            }
            "--check" -> check = true
            "--help", "-h" -> {
                println(
                    "Usage: dictgen [--opencc-dir <dir>] [--output-dir <dir>] [--check]\n" +
                        "  --opencc-dir  OpenCC clone root (default: <cwd>/OpenCC)\n" +
                        "  --output-dir  packaged resources dir (default: " +
                        "<cwd>/sokkuri-runtime/src/commonMain/resources)\n" +
                        "  --check       fail with a drift report instead of writing files",
                )
                exitProcess(0)
            }
            else -> throw DictgenException("unknown argument: ${args[index]} (see --help)")
        }
        index += 1
    }
    return DictgenOptions(openccDir, outputDir, check)
}

private fun requireValue(args: Array<String>, index: Int, flag: String): String {
    if (index >= args.size) throw DictgenException("$flag requires a value")
    return args[index]
}

/**
 * One generation run: rewrites the 16 packaged configs, compiles every
 * dictionary they reference, runs the consistency checks, then either
 * writes the files or diffs them against [DictgenOptions.outputDir].
 *
 * @return process exit code: 0 success, 1 generation failure or drift
 */
internal fun run(options: DictgenOptions): Int {
    if (!options.openccDir.isDirectory) {
        System.err.println("OpenCC clone not found at ${options.openccDir.absolutePath}")
        System.err.println("Clone https://github.com/BYVoid/OpenCC there, or pass --opencc-dir")
        return 1
    }
    return try {
        val outputs = generate(options.openccDir)
        if (options.check) checkAgainstDisk(outputs, options.outputDir) else {
            writeAll(outputs, options.outputDir)
            0
        }
    } catch (e: DictgenException) {
        System.err.println(e.message)
        1
    } catch (e: com.generalk1ng.sokkuri.SokkuriException) {
        System.err.println(e.message)
        1
    }
}

/** Builds the full output set: `config/<stem>.json` + `dictionary/<name>.sok`. */
private fun generate(openccDir: File): Map<String, ByteArray> {
    val outputs = LinkedHashMap<String, ByteArray>()
    val referenced = LinkedHashSet<String>()
    val generator = DictionaryGenerator(openccDir)

    for (config in SokkuriConfig.entries) {
        val source = openccDir.resolve("data/config/${config.stem}.json")
        if (!source.isFile) throw DictgenException("missing config: ${source.path}")
        val rewritten = ConfigRewriter.rewrite(source.readText())
        outputs["config/${config.stem}.json"] = rewritten.encodeToByteArray()
        collectReferenced(rewritten, referenced)
    }
    ConsistencyCheck.verifyConfigStems(SokkuriConfig.entries.map { it.stem }.toSet())

    for (baseName in referenced.sorted()) {
        val (fileName, bytes) = generator.compile(generator.recipeFor(baseName))
        outputs["dictionary/$fileName"] = bytes
    }
    ConsistencyCheck.verifyDictionaryClosure(referenced, outputs.keys
        .filter { it.startsWith("dictionary/") }
        .map { it.removePrefix("dictionary/").removeSuffix(".sok") }
        .toSet())
    return outputs
}

/** Collects dictionary basenames (without extension) from a rewritten config. */
private fun collectReferenced(rewrittenJson: String, out: MutableSet<String>) {
    val document = JsonSupport.opencc
        .decodeFromString(ConfigDocument.serializer(), rewrittenJson)
    fun visit(dict: DictDocument) {
        when (dict) {
            is DictDocument.Group -> dict.dicts.forEach(::visit)
            is DictDocument.Sok -> out.add(dict.file.removePrefix("dictionary/").removeSuffix(".sok"))
            is DictDocument.Text -> out.add(dict.file.removePrefix("dictionary/").removeSuffix(".txt"))
            is DictDocument.Ocd -> out.add(dict.file.removePrefix("dictionary/").removeSuffix(".ocd"))
            is DictDocument.Ocd2 -> out.add(dict.file.removePrefix("dictionary/").removeSuffix(".ocd2"))
            is DictDocument.Inline -> Unit
        }
    }
    document.normalization.forEach { visit(it.dict) }
    document.segmentation?.dict?.let(::visit)
    document.conversionChain.forEach { visit(it.dict) }
}

private fun writeAll(outputs: Map<String, ByteArray>, outputDir: File) {
    for ((relative, bytes) in outputs) {
        val target = outputDir.resolve(relative)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }
}

/**
 * Compares [outputs] with the files under [outputDir]: missing files,
 * content differences, and stale on-disk files are all drift. Nothing is
 * written. A drift report (up to 20 entries) goes to stderr.
 */
private fun checkAgainstDisk(outputs: Map<String, ByteArray>, outputDir: File): Int {
    val drift = LinkedHashSet<String>()
    for ((relative, bytes) in outputs) {
        val onDisk = outputDir.resolve(relative)
        if (!onDisk.isFile) {
            drift.add("missing: $relative")
        } else if (!onDisk.readBytes().contentEquals(bytes)) {
            drift.add("different: $relative")
        }
    }
    val expected = outputs.keys.toSet()
    for (dir in listOf("config", "dictionary")) {
        val children = outputDir.resolve(dir).list()?.toSet().orEmpty()
        for (child in children.sorted()) {
            if ("$dir/$child" !in expected) drift.add("stale: $dir/$child")
        }
    }
    if (drift.isEmpty()) {
        return 0
    }
    System.err.println("dictgen: packaged resources drifted from the OpenCC sources:")
    for (entry in drift.take(20)) {
        System.err.println("  $entry")
    }
    if (drift.size > 20) {
        System.err.println("  ... and ${drift.size - 20} more")
    }
    System.err.println("run :tools:dictgen:run to regenerate")
    return 1
}

/** CLI entry point. */
fun main(args: Array<String>) {
    exitProcess(run(parseArgs(args, File(System.getProperty("user.dir")))))
}
