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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.random.Random

/**
 * Builds the shared input corpus: every input from the upstream golden
 * test file, a fixed set of edge cases, and a seeded random sample over an
 * alphabet chosen to reach the parts of the pipeline that matter.
 *
 * Inputs are single lines and UTF-8 encodable by construction — the harness
 * reads one input per line, and a Kotlin string holding an unpaired surrogate
 * has no UTF-8 form to hand it. That boundary is the one place the two
 * implementations are not comparable at all, since upstream's converter takes
 * UTF-8 and throws `InvalidUTF8` where Sokkuri reads a `CharArray`.
 */
internal object Corpus {

    private val IDS_OPERATORS =
        (0x2FF0..0x2FFF).map { Char(it).toString() }

    /**
     * Fragments weighted toward material that exercises matching: keys that
     * collide inside phrases, both scripts of the same word, IDS operators of
     * every arity, and the tofu-risk characters whose default handling is a
     * registered divergence.
     */
    private val FRAGMENTS = listOf(
        "太后", "头发", "干燥", "鼠标", "内存", "软件", "网络", "硬盘", "打印",
        "服务器", "面包机", "干", "后", "发", "里", "只", "面", "内", "网",
        "软", "硬", "台", "着", "了", "的",
        "太後", "頭髮", "乾燥", "滑鼠", "記憶體", "軟體", "網路", "硬碟",
        "列印", "伺服器", "麵包機", "裏", "裡", "乾", "隻", "麪", "麵",
        "殢", "圞", "樠",
        "𠀀", "𡃁", "𣨼", "𪢮", "𣗊",
        "a", "Z", "0", "9", ",", ".", "!", "-", "_", " ", "  ", "\t",
        "hello", "123", "，", "。", "、", "；", "：", "！", "？", "「", "」",
    ) + IDS_OPERATORS

    /**
     * Edge cases the random sample would rarely produce, chosen to land on
     * behaviour that is easy to get subtly wrong.
     */
    private val EDGE_CASES = listOf(
        // IDS: leading, mid-run, parenthesized, every arity, incomplete
        "⿰钅只", "a⿾证", "（⿰钅只）", "⿱⿰abc", "⿰ab猫", "hi（⿰钅只）there",
        "太后⿰钅只头发", "x⿲abc干燥", "⿿单", "⿾a", "⿰", "⿰a", "⿲", "⿳ab",
        // tofu-risk characters and their supplementary-plane outcomes
        "殢", "圞", "樠", "殢圞樠", "𣨼", "𪢮", "𣗊",
        // non-BMP and the BMP range adjacent to the surrogate boundary
        "𠀀", "𡃁", "𐈀", "𠀀a𡃁", "\uE000", "\uE000a", "a\uE000", "\uD7FF", "\uFFFF",
        // empty, whitespace-only, and boundary-length inputs
        "", " ", "   ", "\t", "a", "abc", "12345", "-_=+",
        // punctuation-only and mixed-script runs
        "。，、；：！？", "内存里的一只烤面包机正在读取打印服务器的硬盘。",
        "鼠标里面的硅二极管坏了，导致光标分辨率降低。",
        "软件和网络", "太后的头发干燥", "abc太后def头发ghi",
        "干燥。鼠标！", "  空格开头", "结尾空格  ",
        "一".repeat(200), "a".repeat(200),
    )

    fun build(openccDir: File, randomCount: Int, seed: Long, extra: File?): List<String> {
        val inputs = LinkedHashSet<String>()
        inputs += goldenInputs(openccDir)
        inputs += EDGE_CASES
        inputs += randomInputs(randomCount, seed)
        extra?.let { inputs += it.readLines() }
        return inputs.filter(::isComparable)
    }

    /**
     * Every `input` field of the upstream test corpus. The file is JSONC
     * (it carries trailing commas), so it is relaxed before parsing.
     */
    private fun goldenInputs(openccDir: File): List<String> {
        val file = File(openccDir, "test/testcases/testcases.json")
        if (!file.isFile) return emptyList()
        val root = Json.parseToJsonElement(relaxJsonc(file.readText()))
        val cases = when (root) {
            is JsonObject -> root["cases"] ?: return emptyList()
            else -> root
        }
        return (cases as? JsonArray).orEmpty().mapNotNull { element ->
            ((element as? JsonObject)?.get("input") as? JsonPrimitive)?.content
        }
    }

    private fun randomInputs(count: Int, seed: Long): List<String> {
        if (count <= 0) return emptyList()
        val random = Random(seed)
        return List(count) {
            val length = random.nextInt(0, 25)
            buildString {
                repeat(length) { append(FRAGMENTS[random.nextInt(FRAGMENTS.size)]) }
            }
        }
    }

    /**
     * Whether both sides can be handed this input. Newlines would break the
     * line-oriented harness, and text that will not survive UTF-8 encoding —
     * an unpaired surrogate — has no representation on the upstream side.
     */
    private fun isComparable(input: String): Boolean {
        if (input.contains('\n') || input.contains('\r')) return false
        return input.encodeToByteArray().decodeToString() == input
    }

    /** Drops comments and trailing commas, the two JSONC relaxations the file uses. */
    private fun relaxJsonc(text: String): String {
        val withoutComments = StringBuilder(text.length)
        var i = 0
        var inString = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString && c == '\\' -> {
                    withoutComments.append(c)
                    if (i + 1 < text.length) withoutComments.append(text[i + 1])
                    i += 2
                }
                c == '"' -> {
                    inString = !inString
                    withoutComments.append(c)
                    i++
                }
                !inString && c == '/' && i + 1 < text.length && text[i + 1] == '/' -> {
                    while (i < text.length && text[i] != '\n') i++
                }
                !inString && c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i += 2
                }
                else -> {
                    withoutComments.append(c)
                    i++
                }
            }
        }
        return Regex(",(\\s*[}\\]])").replace(withoutComments.toString()) { it.groupValues[1] }
    }
}

/** The profile stems both sides are run over, in a stable order. */
internal val PROFILE_STEMS: List<String> =
    listOf(
        "s2t", "t2s", "s2tw", "s2twp", "tw2s", "tw2sp", "t2tw", "tw2t",
        "s2hk", "s2hkp", "hk2s", "hk2sp", "t2hk", "hk2t", "jp2t", "t2jp",
    )
