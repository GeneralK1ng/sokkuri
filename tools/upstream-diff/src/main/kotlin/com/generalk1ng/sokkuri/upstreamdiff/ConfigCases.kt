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

/** One config document under test; [what] says which behavior it probes. */
internal data class ConfigCase(val name: String, val what: String, val json: String)

/**
 * The config comparison's inputs: fixture dictionaries, probe texts, the
 * case catalog, and the cases whose outcomes are known to differ.
 *
 * Every case refers to dictionaries by file name only, so both sides
 * resolve them against the same fixture directory, and only the `text` and
 * `inline` types both implementations load are used, except where a type's
 * absence on one side is the point.
 */
internal object ConfigCases {

    /** Text dictionaries written into the fixture directory, by file name. */
    val fixtures: Map<String, String> = mapOf(
        // Deliberately unsorted: both sides sort a text dictionary on load.
        "chars.txt" to "# one-to-many characters\n后\t後 后\n干\t幹 乾 干\n发\t發 髮\n面\t麵 面\n",
        "phrases.txt" to "头发\t頭髮\n干燥\t乾燥\n后来\t後來\n面包\t麵包\n",
        "regional.txt" to "頭髮\t頭髮R\n麵包\t麵包R\n",
        "tofu.txt" to "后\t后T\n干燥\t乾燥T\n",
        "empty.txt" to "",
        "comments.txt" to "# leading comment\n\n# another\n干\t乾\n\n",
        "crlf.txt" to "干\t乾\r\n发\t髮\r\n",
        "bom.txt" to "﻿干\t乾\n发\t髮\n",
        "spaces.txt" to "干\t乾  幹\n发\t髮 \n",
        "leading-space.txt" to "干\t 乾\n",
        "duplicate-key.txt" to "干\t乾\n干\t幹\n",
        "missing-tab.txt" to "干 乾\n",
        "missing-value.txt" to "干\t\n",
        "empty-key.txt" to "\t乾\n",
    )

    /**
     * Fixtures copied from the reference build's compiled dictionaries:
     * fixture file name to the file in the build's data directory. They
     * give upstream a dictionary in its own binary format to load.
     */
    val referenceFixtures: Map<String, String> = mapOf("variants.ocd2" to "TWVariants.ocd2")

    /** Converted by every config both sides accept; outputs are compared in order. */
    val probes: List<String> = listOf(
        "干燥的头发后来变了",
        "面包和干面",
        "頭髮和麵包",
        "发",
        "⿰干发",
        "",
        "plain ascii",
        // Converted only by the ocd2 fixture (TWVariants: 僞 to 偽).
        "僞裝",
    )

    private fun text(file: String, extra: String = "") = """{"type": "text", "file": "$file"$extra}"""

    private fun tofu(file: String) = text(file, """, "may_output_tofu": true""")

    private fun inline(entries: String, extra: String = "") = """{"type": "inline", "entries": $entries$extra}"""

    private fun group(policy: String?, vararg dicts: String): String {
        val policyField = policy?.let { """"match_policy": "$it", """ } ?: ""
        return """{"type": "group", $policyField"dicts": [${dicts.joinToString(", ")}]}"""
    }

    private fun chain(vararg dicts: String): String =
        """{"conversion_chain": [${dicts.joinToString(", ") { """{"dict": $it}""" }}]}"""

    private fun stage(dict: String) = """{"dict": $dict}"""

    private fun segmented(segmentationDict: String, vararg dicts: String): String {
        val stages = dicts.joinToString(", ") { stage(it) }
        return """{"segmentation": {"type": "mmseg", "dict": $segmentationDict}, "conversion_chain": [$stages]}"""
    }

    private val chars = text("chars.txt")
    private val phrases = text("phrases.txt")
    private val regional = text("regional.txt")

    private fun MutableList<ConfigCase>.case(name: String, what: String, json: String) {
        add(ConfigCase(name, what, json))
    }

    val all: List<ConfigCase> = buildList {
        // Structures both sides should accept, then convert identically.
        case("minimal", "one text dictionary", chain(chars))
        case("phrases-then-characters", "short_circuit group of phrases over characters",
            chain(group("short_circuit", phrases, chars)))
        case("default-policy", "a group without match_policy defaults to short_circuit",
            chain(group(null, phrases, chars)))
        case("union", "a union group takes the longest match across children", chain(group("union", chars, phrases)))
        case("nested-groups", "groups nest", chain(group("short_circuit", group("union", phrases, regional), chars)))
        case("two-stages", "the second stage converts the first stage's output",
            chain(group(null, phrases, chars), regional))
        case("segmentation", "mmseg segmentation bounds every stage's matches",
            segmented(phrases, group(null, phrases, chars)))
        case("inline", "an inline dictionary", chain(inline("""{"干": "乾", "头发": "頭髮"}""")))
        case("inline-in-group", "an inline dictionary inside a group",
            chain(group(null, inline("""{"头发": "HAIR"}"""), chars)))
        case("normalization", "normalization runs before segmentation and the chain",
            """{"normalization": [${stage(inline("""{"髮": "发"}"""))}], "conversion_chain": [${stage(chars)}]}""")
        case("empty-normalization", "an empty normalization array",
            """{"normalization": [], "conversion_chain": [${stage(chars)}]}""")
        case("empty-chain", "an empty conversion chain is the identity", """{"conversion_chain": []}""")
        case("unknown-members", "unknown members anywhere are ignored",
            """{"name": "x", "extra": [1, 2], """ +
                """"conversion_chain": [{"dict": ${text("chars.txt", ", \"note\": \"y\"")}, "also": true}]}""")
        case("name-not-a-string", "a non-string name", """{"name": 5, "conversion_chain": []}""")
        case("duplicate-member", "a member given twice: which occurrence wins",
            """{"conversion_chain": [${stage(chars)}], "conversion_chain": []}""")
        case("jsonc-comments", "line and block comments",
            "{\n  // the chain\n  \"conversion_chain\": [ /* one stage */ ${stage(chars)}]\n}")
        case("jsonc-trailing-commas", "trailing commas in arrays and objects",
            """{"conversion_chain": [{"dict": $chars,},],}""")
        case("stage-not-object", "a chain member that is not an object",
            """{"conversion_chain": [1, ${stage(chars)}]}""")

        // Tofu-risk filtering; every case runs under both settings.
        case("tofu-child", "a tofu-risk child drops out of its group", chain(group(null, tofu("tofu.txt"), chars)))
        case("tofu-stage", "a stage whose dictionary is tofu-risk drops out", chain(tofu("tofu.txt"), chars))
        case("tofu-group", "a group whose children are all tofu-risk drops out",
            chain(group(null, tofu("tofu.txt")), chars))
        case("tofu-segmentation", "segmentation loads tofu-risk dictionaries regardless of the setting",
            segmented(tofu("tofu.txt"), chars))
        case("tofu-false", "an explicit may_output_tofu: false",
            chain(text("chars.txt", """, "may_output_tofu": false""")))

        // Text dictionary content.
        case("text-empty", "an empty text dictionary", chain(text("empty.txt")))
        case("text-comments", "comment and blank lines", chain(text("comments.txt")))
        case("text-crlf", "CRLF line endings", chain(text("crlf.txt")))
        case("text-bom", "a UTF-8 byte order mark", chain(text("bom.txt")))
        case("text-spaces", "repeated and trailing spaces between candidates", chain(text("spaces.txt")))
        case("text-leading-space", "a space before the first candidate", chain(text("leading-space.txt")))
        case("text-duplicate-key", "a key listed twice", chain(text("duplicate-key.txt")))
        case("text-missing-tab", "a line without a tab", chain(text("missing-tab.txt")))
        case("text-missing-value", "a key without candidates", chain(text("missing-value.txt")))
        case("text-empty-key", "an empty key", chain(text("empty-key.txt")))
        case("text-missing-file", "a dictionary file that does not exist", chain(text("absent.txt")))

        // Documents both sides should reject.
        case("not-json", "malformed JSON", """{"conversion_chain": [""")
        case("trailing-garbage", "content after the root value", """{"conversion_chain": []} x""")
        case("single-quotes", "single-quoted strings are not JSON", """{'conversion_chain': []}""")
        case("unquoted-key", "unquoted keys are not JSON", """{conversion_chain: []}""")
        case("root-array", "the root must be an object", "[]")
        case("root-string", "the root must be an object", "\"x\"")
        case("missing-chain", "conversion_chain is required", "{}")
        case("chain-not-array", "conversion_chain must be an array", """{"conversion_chain": {}}""")
        case("stage-without-dict", "a stage needs a dict", """{"conversion_chain": [{}]}""")
        case("dict-not-object", "dict must be an object", """{"conversion_chain": [{"dict": "chars.txt"}]}""")
        case("dict-without-type", "dict needs a type", chain("""{"file": "chars.txt"}"""))
        case("dict-type-not-string", "type must be a string", chain("""{"type": 1, "file": "chars.txt"}"""))
        case("dict-unknown-type", "an unknown dictionary type", chain("""{"type": "binary", "file": "chars.txt"}"""))
        case("text-without-file", "a text dictionary needs a file", chain("""{"type": "text"}"""))
        case("text-file-not-string", "file must be a string", chain("""{"type": "text", "file": 1}"""))
        case("tofu-not-bool", "may_output_tofu must be a boolean",
            chain(text("chars.txt", """, "may_output_tofu": "yes"""")))
        case("group-without-dicts", "a group needs dicts", chain("""{"type": "group"}"""))
        case("group-dicts-not-array", "dicts must be an array", chain("""{"type": "group", "dicts": {}}"""))
        case("group-member-not-object", "every member of dicts must be an object",
            chain("""{"type": "group", "dicts": ["chars.txt"]}"""))
        case("group-empty", "a group without members", chain("""{"type": "group", "dicts": []}"""))
        case("group-unknown-policy", "an unknown match_policy", chain(group("longest", chars)))
        case("group-unknown-policy-filtered", "an unknown match_policy even when every member is filtered out",
            chain(group("longest", tofu("tofu.txt"))))
        case("group-policy-not-string", "match_policy must be a string",
            chain("""{"type": "group", "match_policy": 1, "dicts": [$chars]}"""))
        case("inline-tofu-true", "inline dictionaries reject may_output_tofu",
            chain(inline("""{"干": "乾"}""", """, "may_output_tofu": true""")))
        case("inline-tofu-false", "inline dictionaries reject may_output_tofu by presence",
            chain(inline("""{"干": "乾"}""", """, "may_output_tofu": false""")))
        case("inline-without-entries", "an inline dictionary needs entries", chain("""{"type": "inline"}"""))
        case("inline-entries-not-object", "entries must be an object", chain(inline("""["干"]""")))
        case("inline-empty-key", "an inline key must not be empty", chain(inline("""{"": "乾"}""")))
        case("inline-empty-value", "an inline value must not be empty", chain(inline("""{"干": ""}""")))
        case("inline-value-not-string", "an inline value must be a string", chain(inline("""{"干": 1}""")))
        case("inline-duplicate-key", "an inline key listed twice", chain(inline("""{"干": "乾", "干": "幹"}""")))
        case("segmentation-not-object", "segmentation must be an object",
            """{"segmentation": "mmseg", "conversion_chain": []}""")
        case("segmentation-without-type", "segmentation needs a type",
            """{"segmentation": {"dict": $phrases}, "conversion_chain": []}""")
        case("segmentation-without-dict", "mmseg needs a dict",
            """{"segmentation": {"type": "mmseg"}, "conversion_chain": []}""")
        case("segmentation-plugin", "a segmentation type other than mmseg",
            """{"segmentation": {"type": "jieba"}, "conversion_chain": []}""")
        case("normalization-not-array", "normalization must be an array",
            """{"normalization": {}, "conversion_chain": []}""")

        // Binary dictionary types.
        case("ocd2", "the marisa format upstream ships", chain("""{"type": "ocd2", "file": "variants.ocd2"}"""))
        case("ocd2-absent", "a missing ocd2 file", chain("""{"type": "ocd2", "file": "absent.ocd2"}"""))
        case("sok", "Sokkuri's own binary format", chain("""{"type": "sok", "file": "absent.sok"}"""))
    }

    /**
     * Cases whose outcomes differ by design, with the decision behind each
     * (docs/architecture.md §7 unless noted).
     */
    val deliberateDivergences: Map<String, String> = mapOf(
        "ocd2" to "#3: ocd and ocd2 are not ported, their byte order and word size not being portable; " +
            "Sokkuri reports Unsupported where upstream loads the file",
        "segmentation-plugin" to "#9: segmentation plugins are never loaded; upstream fails to find the plugin " +
            "library, Sokkuri reports Unsupported",
        "sok" to "§4.2: sok is Sokkuri's own production format, a type upstream does not know",
    )

    /**
     * Parity gaps this comparison found that are not yet closed: Sokkuri
     * should behave like upstream here. Listed so the run stays usable;
     * fixing one makes its entry stale, which fails the run until removed.
     */
    val knownGaps: Map<String, String> = mapOf(
        "duplicate-member" to "rapidjson resolves a repeated member to its first occurrence, " +
            "kotlinx-serialization to its last",
        "stage-not-object" to "upstream skips chain members that are not objects; Sokkuri rejects the config",
        "text-leading-space" to "upstream splits candidates on every space, so a space after the tab makes the " +
            "empty string the default candidate; Sokkuri drops empty candidates",
        "text-missing-value" to "upstream maps a key with nothing after the tab to the empty string; " +
            "Sokkuri rejects the dictionary",
        "text-empty-key" to "upstream keeps an entry with an empty key, which never matches; " +
            "Sokkuri rejects the dictionary",
        "inline-duplicate-key" to "upstream rejects an inline key listed twice; Sokkuri's JSON decoding keeps " +
            "the last occurrence",
    )
}
