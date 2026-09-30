@file:OptIn(SokkuriInternalApi::class)

package com.generalk1ng.sokkuri

/**
 * Reads a file from this module's test resources (`src/commonTest/
 * resources`), returning `null` when absent. The single IO seam of the
 * commonTest suite — the golden harness and inspection smoke tests must not
 * leak `java.io`/`platform.posix` into common code.
 *
 * Deliberately delegates to the production [defaultResourceLoader] rather
 * than adding a test-only expect/actual pair: the platform actuals already
 * resolve test resources on every test runtime (JVM/Android host: test
 * resources sit on the class path; iOS: the Gradle wiring copies processed
 * test resources beside the test binary, where `NSBundle.mainBundle`
 * resolves), and reusing the real loading path keeps the constitution's
 * expect/actual restriction (R5) to its two production sites.
 */
internal fun readTestResource(path: String): ByteArray? = defaultResourceLoader().load(path)
