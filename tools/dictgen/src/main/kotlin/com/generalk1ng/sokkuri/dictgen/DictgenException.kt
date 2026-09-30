package com.generalk1ng.sokkuri.dictgen

/**
 * A generation-time failure (missing sources, derivation conflicts,
 * consistency violations). Caught at the CLI boundary and reported without
 * a stack trace — these messages are build diagnostics, not crashes.
 */
internal class DictgenException internal constructor(
    message: String,
) : Exception(message)
