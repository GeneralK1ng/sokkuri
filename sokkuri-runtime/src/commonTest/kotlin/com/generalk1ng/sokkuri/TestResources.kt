package com.generalk1ng.sokkuri

/**
 * Reads a file from the test resources of this module (`src/commonTest/
 * resources`), returning `null` when absent. The single IO seam of the
 * commonTest suite — the golden harness and inspection smoke tests must not
 * leak `java.io`/`platform.posix` into common code.
 */
internal expect fun readTestResource(path: String): ByteArray?
