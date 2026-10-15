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

package com.generalk1ng.sokkuri.config

/**
 * A structural reading of a comment-free JSON text that keeps what
 * kotlinx-serialization's parser discards: every member of an object in
 * order, repeated keys included, with where it sits in the text.
 *
 * It exists to give repeated members the meaning they have upstream, not to
 * validate: rapidjson keeps every member and `FindMember` returns the first,
 * while kotlinx-serialization keeps the last. Anything this reader cannot
 * follow yields `null`, and the caller hands the text to
 * kotlinx-serialization unchanged, which then reports the error in its usual
 * terms, so the accepted grammar is never widened here.
 */
internal object JsonStructure {

    /** Deeper nesting than any config needs; reading stops instead of exhausting the stack. */
    private const val MAX_DEPTH = 256

    sealed interface Node {
        /** Index just past the value in the text. */
        val end: Int
    }

    class ObjectNode(val members: List<Member>, override val end: Int) : Node {
        /** The member upstream's `FindMember` would return: the first with [key]. */
        fun first(key: String): Node? = members.firstOrNull { it.key == key }?.value
    }

    /**
     * One member; [start] is where its removal would begin: just past the
     * previous member's value, or past the brace for the first member.
     */
    class Member(val key: String, val value: Node, val start: Int)

    class ArrayNode(val items: List<Node>, override val end: Int) : Node

    class TextNode(val value: String, override val end: Int) : Node

    class ScalarNode(override val end: Int) : Node

    /** The structure of [json], or `null` when it cannot be followed. */
    fun read(json: String): Node? = try {
        Reader(json).document()
    } catch (_: Malformed) {
        null
    }

    /**
     * What upstream reads from [json]:
     * - [json] is the text with every repeated member removed except the
     *   first occurrence in each object, recursively;
     * - [repeatedInlineKeys] maps the [DictPath] of each inline dictionary
     *   upstream's `ParseDict` could reach to the first key its `entries`
     *   list more than once, which upstream rejects when it loads that
     *   dictionary. Whether it is loaded at all (tofu filtering) is the
     *   parser's call, so the repeats are reported, not thrown.
     */
    class Reading(val json: String, val repeatedInlineKeys: Map<String, String>)

    /** Reads [json] as upstream does; unchanged and with no repeats when the text cannot be followed. */
    fun readAsUpstream(json: String): Reading {
        val root = read(json) ?: return Reading(json, emptyMap())
        val removals = ArrayList<IntRange>()
        collectRepeats(root, removals)
        return Reading(withoutRanges(json, removals), repeatedInlineKeys(root))
    }

    /** [json] with every repeated member removed except the first occurrence: the document upstream reads. */
    fun firstOccurrencesOnly(json: String): String = readAsUpstream(json).json

    private fun withoutRanges(json: String, removals: List<IntRange>): String {
        if (removals.isEmpty()) return json
        val out = StringBuilder(json.length)
        var copied = 0
        for (range in removals.sortedBy { it.first }) {
            out.append(json, copied, range.first)
            copied = range.last + 1
        }
        out.append(json, copied, json.length)
        return out.toString()
    }

    /**
     * Follows the dictionaries upstream's `ParseDict` reaches, by first
     * occurrence, as [DictPath] names them: the segmentation dictionary, each
     * object member of the two conversion lists, and group members.
     */
    private fun repeatedInlineKeys(root: Node): Map<String, String> {
        val document = root as? ObjectNode ?: return emptyMap()
        val repeats = LinkedHashMap<String, String>()
        fun visit(node: Node?, path: String) {
            val dict = node as? ObjectNode ?: return
            when ((dict.first("type") as? TextNode)?.value) {
                "inline" -> {
                    val entries = dict.first("entries") as? ObjectNode ?: return
                    val seen = HashSet<String>()
                    entries.members.firstOrNull { !seen.add(it.key) }?.let { repeats[path] = it.key }
                }
                "group" -> (dict.first("dicts") as? ArrayNode)?.items
                    ?.forEachIndexed { index, member -> visit(member, DictPath.groupMember(path, index)) }
            }
        }
        (document.first("segmentation") as? ObjectNode)?.let { visit(it.first("dict"), DictPath.SEGMENTATION) }
        for (list in listOf(DictPath.CONVERSION_CHAIN, DictPath.NORMALIZATION)) {
            val stages = (document.first(list) as? ArrayNode)?.items?.filterIsInstance<ObjectNode>().orEmpty()
            stages.forEachIndexed { index, stage -> visit(stage.first("dict"), DictPath.stage(list, index)) }
        }
        return repeats
    }

    private fun collectRepeats(node: Node, removals: MutableList<IntRange>) {
        when (node) {
            is ObjectNode -> {
                val seen = HashSet<String>()
                for (member in node.members) {
                    if (seen.add(member.key)) {
                        collectRepeats(member.value, removals)
                    } else {
                        // Never the first member, so the span starts at the
                        // end of a preceding value and swallows the comma.
                        removals += member.start until member.value.end
                    }
                }
            }
            is ArrayNode -> node.items.forEach { collectRepeats(it, removals) }
            is TextNode, is ScalarNode -> Unit
        }
    }

    private class Malformed : RuntimeException()

    private class Reader(private val text: String) {
        private var pos = 0

        fun document(): Node {
            skipWhitespace()
            return value(depth = 0)
        }

        private fun value(depth: Int): Node {
            if (depth > MAX_DEPTH || pos >= text.length) throw Malformed()
            return when (text[pos]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                else -> scalar()
            }
        }

        private fun obj(depth: Int): ObjectNode {
            pos++ // '{'
            val members = ArrayList<Member>()
            var memberStart = pos
            while (true) {
                skipWhitespace()
                if (peek() == '}') {
                    pos++
                    return ObjectNode(members, pos)
                }
                if (peek() != '"') throw Malformed()
                val key = string().value
                skipWhitespace()
                if (peek() != ':') throw Malformed()
                pos++
                skipWhitespace()
                val member = value(depth + 1)
                members += Member(key, member, memberStart)
                memberStart = member.end
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> Unit
                    else -> throw Malformed()
                }
            }
        }

        private fun array(depth: Int): ArrayNode {
            pos++ // '['
            val items = ArrayList<Node>()
            while (true) {
                skipWhitespace()
                if (peek() == ']') {
                    pos++
                    return ArrayNode(items, pos)
                }
                items += value(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> Unit
                    else -> throw Malformed()
                }
            }
        }

        /** A string literal, decoded, so keys compare as rapidjson compares them. */
        private fun string(): TextNode {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) throw Malformed()
                val c = text[pos++]
                when (c) {
                    '"' -> return TextNode(out.toString(), pos)
                    '\\' -> {
                        if (pos >= text.length) throw Malformed()
                        when (val escaped = text[pos++]) {
                            '"', '\\', '/' -> out.append(escaped)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw Malformed()
                                val code = text.substring(pos, pos + 4).toIntOrNull(16) ?: throw Malformed()
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> throw Malformed()
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        /** A number or literal, kept only as a span: nothing here depends on its value. */
        private fun scalar(): ScalarNode {
            val start = pos
            while (pos < text.length && text[pos] !in SCALAR_ENDS && !text[pos].isWhitespace()) pos++
            if (pos == start) throw Malformed()
            return ScalarNode(pos)
        }

        private fun peek(): Char = if (pos < text.length) text[pos] else throw Malformed()

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private companion object {
            const val SCALAR_ENDS = ",]}:"
        }
    }
}

/**
 * Names a dictionary by where it sits in a config, the same way for the
 * structural reading ([JsonStructure.readAsUpstream]) and the parser, so the
 * two agree on which inline dictionary a repeat belongs to. Indices in the
 * conversion lists count object members only, matching the decoded lists,
 * which skip the rest.
 */
internal object DictPath {
    const val SEGMENTATION = "segmentation.dict"
    const val CONVERSION_CHAIN = "conversion_chain"
    const val NORMALIZATION = "normalization"

    fun stage(list: String, index: Int): String = "$list[$index].dict"

    fun groupMember(group: String, index: Int): String = "$group.dicts[$index]"
}
