package com.generalk1ng.sokkuri

import kotlin.test.Test

/**
 * Records the pilot's text-dictionary baseline: first-create (cold decode)
 * latency, warm-create latency (exercising the process-wide
 * [DictionaryCache] sharing, D3), and steady-state conversion throughput.
 *
 * The numbers are printed to the test log and transcribed into
 * `docs/milestones/m1-dictgen-sok.md` §7; the `.sok` format (milestone step
 * 5.1) must decode no slower than this baseline. Nothing is asserted — this
 * is a measuring instrument, not a gate.
 */
class PilotBaselineTest {

    @Test
    fun recordsTextDictionaryBaseline() {
        val runtime = Runtime.getRuntime()

        // Cold: nothing decoded yet in this process.
        System.gc()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()
        val coldStart = System.nanoTime()
        val converter = Sokkuri.create(Config.S2T)
        val coldMillis = (System.nanoTime() - coldStart) / 1_000_000
        System.gc()
        val heapAfter = runtime.totalMemory() - runtime.freeMemory()

        // Warm: dictionaries are shared process-wide, so creation should be
        // dominated by the config read.
        val warmMillis = (1..5).map {
            val start = System.nanoTime()
            Sokkuri.create(Config.S2T)
            (System.nanoTime() - start) / 1_000_000
        }.sorted()

        // Throughput: a mixed Simplified sentence, converted repeatedly.
        val sample = "鼠标里面的硅二极管坏了，需要更换。软件开发和硬件维护都需要严谨的态度。" +
            "自干五扇风耳朵，铺眉搧眼。"
        val iterations = 20_000
        val throughputStart = System.nanoTime()
        repeat(iterations) { converter.convert(sample) }
        val perConversionMicros =
            (System.nanoTime() - throughputStart) / 1_000.0 / iterations

        println(
            "[pilot-baseline] coldCreate=${coldMillis}ms " +
                "warmCreateMedian=${warmMillis[2]}ms " +
                "heapDelta≈${(heapAfter - heapBefore) / 1024}KiB " +
                "conversion=${"%.2f".format(perConversionMicros)}µs/op",
        )
    }
}
