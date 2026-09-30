package com.generalk1ng.sokkuri

/**
 * Behavioral options for a [Sokkuri] instance.
 *
 * @property includeTofuRiskDictionaries mirrors OpenCC's
 *   `--include-tofu-risk-dictionaries`: dictionaries flagged
 *   `may_output_tofu` (e.g. `TSCharactersExt`) are excluded unless this is
 *   `true`. OpenCC's own test corpus runs with this enabled.
 */
public class Options public constructor(
    public val includeTofuRiskDictionaries: Boolean = false,
) {
    public companion object {
        /** The default behavior: tofu-risk dictionaries are excluded. */
        public val DEFAULT: Options = Options()
    }
}
