package com.generalk1ng.sokkuri.config

import com.generalk1ng.sokkuri.SokkuriInternalApi

import kotlinx.serialization.json.Json

/**
 * The lenient JSON configuration parser shared by config decoding.
 *
 * OpenCC parses configurations with exactly `kParseCommentsFlag |
 * kParseTrailingCommasFlag` (rapidjson, see OpenCC `Config.cpp`) — comments
 * and trailing commas tolerated, strictly-quoted JSON otherwise, unknown keys
 * ignored. Its ecosystem files (including `testcases.json`) are JSONC in
 * practice. kotlinx-serialization natively covers trailing commas; comments
 * are stripped by a small string preprocessor that respects string literals.
 * Deliberately *not* `isLenient`: Sokkuri must not accept malformed configs
 * that upstream OpenCC rejects.
 */
@SokkuriInternalApi
public object JsonSupport {

    public val opencc: Json = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
        // OpenCC configs do not use polymorphism beyond the sealed DictDocument.
        classDiscriminator = "type"
    }

    /**
     * Removes `//` and block comments outside string literals. String
     * contents, escapes, and line structure are preserved.
     */
    public fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        var inString = false
        while (i < n) {
            val c = text[i]
            when {
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < n) {
                        out.append(text[i + 1])
                        i += 2
                        continue
                    }
                    if (c == '"') inString = false
                    i += 1
                }
                c == '"' -> {
                    inString = true
                    out.append(c)
                    i += 1
                }
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n') i += 1
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i += 1
                    i = minOf(i + 2, n)
                }
                else -> {
                    out.append(c)
                    i += 1
                }
            }
        }
        return out.toString()
    }
}
