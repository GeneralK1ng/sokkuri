package com.generalk1ng.sokkuri

/**
 * Failure modes of the conversion pipeline.
 *
 * Mirrors the exception taxonomy of OpenCC's C++ core
 * (`FileNotFound` / `InvalidFormat` / `InvalidConfig` for configs),
 * so behavior stays diagnosable for users coming from other OpenCC ports.
 */
public sealed class SokkuriException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** A resource (config or dictionary) could not be located. */
    public class FileNotFound public constructor(
        public val path: String,
        /**
         * Optional diagnostic appended to the message — e.g. the Android
         * resource loader pointing at a missing `Sokkuri.init` call, which
         * otherwise surfaces as a bare not-found and sends users to check
         * their packaging.
         */
        detail: String? = null,
    ) : SokkuriException("Resource not found: $path" + if (detail != null) " ($detail)" else "")

    /**
     * A resource was located but its content violates the expected format.
     *
     * [detail] is exposed (not just embedded in the message) so outer layers
     * can re-contextualize — e.g. the dictionary provider prefixes the
     * decoder's field-level detail with the offending file path.
     */
    public class InvalidFormat public constructor(
        public val detail: String,
    ) : SokkuriException("Invalid format: $detail")

    /** A conversion configuration is structurally invalid. */
    public class InvalidConfig public constructor(
        detail: String,
    ) : SokkuriException("Invalid configuration: $detail")

    /** A feature of OpenCC that this port deliberately does not support. */
    public class Unsupported public constructor(
        detail: String,
    ) : SokkuriException("Unsupported: $detail")
}
