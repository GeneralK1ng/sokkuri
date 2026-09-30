package com.generalk1ng.sokkuri

/**
 * Behavioral options for a [Sokkuri] instance.
 *
 * Construct via [Options.invoke] (`Options { ... }`) or use [DEFAULT]. The
 * builder shape is deliberate: new options are added as `Builder`
 * properties, which keeps existing call sites source- and binary-compatible
 * (the `kotlinx.serialization.Json` precedent), unlike adding constructor
 * parameters to a plain class.
 *
 * @property includeTofuRiskDictionaries mirrors OpenCC's
 *   `--include-tofu-risk-dictionaries`: dictionaries flagged
 *   `may_output_tofu` (e.g. `TSCharactersExt`) are excluded unless this is
 *   `true`. OpenCC's CLI defaults to exclusion; its C++ core and own test
 *   corpus run with inclusion — Sokkuri follows the CLI default deliberately
 *   (registered deviation, docs/architecture.md §7).
 */
public class Options private constructor(
    public val includeTofuRiskDictionaries: Boolean,
) {

    public class Builder {
        /** Dictionaries flagged `may_output_tofu` are excluded unless set. */
        public var includeTofuRiskDictionaries: Boolean = false

        public fun build(): Options = Options(includeTofuRiskDictionaries)
    }

    public companion object {
        /** The default behavior: tofu-risk dictionaries are excluded. */
        public val DEFAULT: Options = Options(includeTofuRiskDictionaries = false)

        /**
         * Builds an instance: `Options { includeTofuRiskDictionaries = true }`.
         */
        public operator fun invoke(builderAction: Builder.() -> Unit): Options =
            Builder().apply(builderAction).build()
    }
}
