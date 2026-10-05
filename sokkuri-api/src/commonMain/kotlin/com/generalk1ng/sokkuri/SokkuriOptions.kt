package com.generalk1ng.sokkuri

/**
 * Behavioral options for a [Sokkuri] instance.
 *
 * Construct via [SokkuriOptions.invoke] (`SokkuriOptions { ... }`) or use
 * [DEFAULT]. The builder shape is deliberate: new options are added as
 * `Builder` properties, which keeps existing call sites source- and
 * binary-compatible (the `kotlinx.serialization.Json` precedent), unlike
 * adding constructor parameters to a plain class.
 *
 * @property includeTofuRiskDictionaries mirrors OpenCC's
 *   `--include-tofu-risk-dictionaries`: dictionaries flagged
 *   `may_output_tofu` (e.g. `TSCharactersExt`) are excluded unless this is
 *   `true`. OpenCC's CLI defaults to exclusion; its C++ core and own test
 *   corpus run with inclusion — Sokkuri follows the CLI default deliberately
 *   (registered deviation, docs/architecture.md §7).
 */
public class SokkuriOptions private constructor(
    public val includeTofuRiskDictionaries: Boolean,
) {

    /**
     * The mutable receiver of [SokkuriOptions.invoke]; each property mirrors an
     * [SokkuriOptions] property and starts at the [DEFAULT] value.
     */
    public class Builder {
        /**
         * Set to `true` to include dictionaries flagged `may_output_tofu`,
         * which matches OpenCC's C++ core and its own test corpus. The default
         * (`false`) matches OpenCC's CLI.
         */
        public var includeTofuRiskDictionaries: Boolean = false

        /** Freezes the current values into an immutable [SokkuriOptions]. */
        public fun build(): SokkuriOptions = SokkuriOptions(includeTofuRiskDictionaries)
    }

    public companion object {
        /** The default behavior: tofu-risk dictionaries are excluded. */
        public val DEFAULT: SokkuriOptions = SokkuriOptions(includeTofuRiskDictionaries = false)

        /**
         * Builds an instance: `SokkuriOptions { includeTofuRiskDictionaries = true }`.
         */
        public operator fun invoke(builderAction: Builder.() -> Unit): SokkuriOptions =
            Builder().apply(builderAction).build()
    }
}
