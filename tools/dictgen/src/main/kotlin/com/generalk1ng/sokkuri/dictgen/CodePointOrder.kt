package com.generalk1ng.sokkuri.dictgen

/**
 * Code-point ordering (architecture invariant I2) and code-point counting,
 * reimplemented here because `tools:dictgen` must not depend on
 * `:sokkuri-engine` (m1-dictgen-sok §3). The comparison is
 * character-for-character aligned with `Utf.compareByCodePoint`; the
 * derivation byte-diff tests (DictionaryDerivationsTest) pin the alignment
 * against upstream script outputs.
 */
internal object CodePointOrder {

    /**
     * Compares [a] and [b] by Unicode code point, the sort order of OpenCC
     * dictionaries (and of Python's `str` sort used by the upstream data
      * scripts). Unlike UTF-16 [String.compareTo], supplementary planes
     * order after all BMP scalars.
     */
    internal fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = codePointAt(a, i)
            val cb = codePointAt(b, j)
            if (ca != cb) return ca - cb
            i += charCount(ca)
            j += charCount(cb)
        }
        return (a.length - i) - (b.length - j)
    }

    /** Number of Unicode code points in [text] (what Python's `len` sees). */
    internal fun codePointLength(text: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            index += charCount(codePointAt(text, index))
            count += 1
        }
        return count
    }

    private fun codePointAt(text: String, index: Int): Int {
        val first = text[index]
        if (first.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
            return ((first.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00) + 0x10000
        }
        return first.code
    }

    private fun charCount(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1
}
