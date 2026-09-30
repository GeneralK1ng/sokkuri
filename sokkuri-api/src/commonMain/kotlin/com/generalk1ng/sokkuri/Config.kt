package com.generalk1ng.sokkuri

/**
 * A built-in conversion profile, one-to-one with an OpenCC configuration
 * (`data/config/s2t.json`). String forms follow OpenCC conventions:
 * [S2T] serializes to `"s2t"`, and `"s2t.json"` resolves back via [fromStem].
 *
 * OpenCC's `*_seal` profiles (seal-script mapping) and the jieba segmentation
 * plugin are not ported; see project documentation.
 */
public enum class Config(
    /**
     * The OpenCC configuration stem (`s2t` for [S2T]); the packaged file is
     * `config/<stem>.json`.
     */
    public val stem: String,
) {
    S2T("s2t"),
    T2S("t2s"),
    S2TW("s2tw"),
    S2TWP("s2twp"),
    TW2S("tw2s"),
    TW2SP("tw2sp"),
    T2TW("t2tw"),
    TW2T("tw2t"),
    S2HK("s2hk"),
    S2HKP("s2hkp"),
    HK2S("hk2s"),
    HK2SP("hk2sp"),
    T2HK("t2hk"),
    HK2T("hk2t"),
    JP2T("jp2t"),
    T2JP("t2jp"),
    ;

    public companion object {
        /**
         * Resolves an OpenCC-style config reference (case-insensitive, with or
         * without the `.json` suffix, e.g. `"s2t"` or `"S2TWP.json"`).
         *
         * @return the matching profile, or `null` when [stem] is unknown.
         */
        public fun fromStem(stem: String): Config? {
            val normalized = stem.removeSuffix(".json").lowercase()
            return entries.firstOrNull { it.stem == normalized }
        }
    }
}
