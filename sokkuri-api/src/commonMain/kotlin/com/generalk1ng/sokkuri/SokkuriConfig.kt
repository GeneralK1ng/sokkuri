package com.generalk1ng.sokkuri

/**
 * A built-in conversion profile, one-to-one with an OpenCC configuration
 * (`data/config/s2t.json`). String forms follow OpenCC conventions:
 * [S2T] serializes to `"s2t"`, and `"s2t.json"` resolves back via [fromStem].
 *
 * Names read `source2target` under OpenCC's abbreviation scheme: `s`
 * Simplified, `t` Traditional (generic), `tw` Taiwan, `hk` Hong Kong, `jp`
 * Japanese (shinjitai). A trailing `p` marks a profile that also converts
 * *vocabulary*: [S2TWP] yields 滑鼠 for 鼠标, whereas [S2TW] only reshapes
 * characters, leaving 鼠標. Regional profiles without `p` rewrite character
 * variants only.
 *
 * OpenCC's `*_seal` profiles (seal-script mapping) and the jieba segmentation
 * plugin are not ported; see project documentation.
 */
public enum class SokkuriConfig(
    /**
     * The OpenCC configuration stem (`s2t` for [S2T]); the packaged file is
     * `config/<stem>.json`. This is OpenCC's own identifier and is not
     * localized.
     */
    public val stem: String,
) {
    /** Simplified → Traditional, generic forms: 内 → 內, 里 → 裏. */
    S2T("s2t"),

    /** Traditional → Simplified: 內 → 内, 裏 → 里. */
    T2S("t2s"),

    /** Simplified → Taiwan character forms only: 裏 → 裡, 麪 → 麵. */
    S2TW("s2tw"),

    /** Simplified → Taiwan forms *and* Taiwan vocabulary: 鼠标 → 滑鼠, 内存 → 記憶體. */
    S2TWP("s2twp"),

    /** Taiwan → Simplified: 裡 → 里. */
    TW2S("tw2s"),

    /** Taiwan → Simplified, also reversing Taiwan vocabulary: 滑鼠 → 鼠标. */
    TW2SP("tw2sp"),

    /** Traditional → Taiwan character forms only: 裏 → 裡, 牀 → 床. */
    T2TW("t2tw"),

    /** Taiwan → Traditional, generic forms: 裡 → 裏. */
    TW2T("tw2t"),

    /**
     * Simplified → Hong Kong character forms: 兌 → 兑, 叄 → 叁. Hong Kong
     * keeps 裏 where Taiwan writes 裡, so this is not [S2TW] under another name.
     */
    S2HK("s2hk"),

    /** Simplified → Hong Kong forms *and* Hong Kong vocabulary. */
    S2HKP("s2hkp"),

    /** Hong Kong → Simplified. */
    HK2S("hk2s"),

    /** Hong Kong → Simplified, also reversing Hong Kong vocabulary. */
    HK2SP("hk2sp"),

    /** Traditional → Hong Kong character forms: 兌 → 兑, 叄 → 叁. */
    T2HK("t2hk"),

    /** Hong Kong → Traditional, generic forms: 兑 → 兌. */
    HK2T("hk2t"),

    /** Japanese shinjitai → Traditional: 円 → 圓, 発 → 發. */
    JP2T("jp2t"),

    /** Traditional → Japanese shinjitai: 圓 → 円, 發 → 発. */
    T2JP("t2jp"),
    ;

    public companion object {
        /**
         * Resolves an OpenCC-style config reference (case-insensitive, with or
         * without the `.json` suffix, e.g. `"s2t"` or `"S2TWP.json"`).
         *
         * @return the matching profile, or `null` when no profile matches.
         */
        public fun fromStem(stem: String): SokkuriConfig? {
            val normalized = stem.removeSuffix(".json").lowercase()
            return entries.firstOrNull { it.stem == normalized }
        }
    }
}
