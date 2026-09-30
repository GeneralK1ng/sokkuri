package com.generalk1ng.sokkuri

/**
 * Marks APIs of Sokkuri's internal layers (`sokkuri-engine`, `sokkuri-config`,
 * `sokkuri-resource`) that are technically public for cross-module wiring
 * but are **not** part of the stable public surface.
 *
 * The `sokkuri` aggregate artifact is the only supported entry point.
 * Layer APIs may change in any release without notice; depending on them
 * directly (outside this project's own modules) opts you into breakage.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This is an internal layer API of Sokkuri and is not part of the stable public surface.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
)
public annotation class SokkuriInternalApi
