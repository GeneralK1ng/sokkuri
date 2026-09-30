@file:OptIn(SokkuriInternalApi::class)

package com.generalk1ng.sokkuri

import com.generalk1ng.sokkuri.resource.SokDictionaryFormat
import com.generalk1ng.sokkuri.resource.TextDictionaryFormat
import java.io.File
import kotlin.test.Test

/**
 * The m1-dictgen-sok §5.1 design gate, measured: the `.sok` binary format
 * must decode no slower than the equivalent upstream text dictionary parses
 * — otherwise the format is a failure and goes back to design.
 *
 * For representative dictionaries (small character table, mid-size table,
 * the largest phrase table) this decodes the OpenCC clone's `.txt` through
 * [TextDictionaryFormat] and the packaged `.sok` through
 * [SokDictionaryFormat], timing both (median of [REPETITIONS] runs, fresh
 * instance per run so the text parse is never warm) and estimating retained
 * heap per instance. Nothing is asserted — the numbers are printed as
 * `[sok-baseline] ...` lines and transcribed into the milestone's §7
 * acceptance table; the gate is judged there.
 *
 * Runs only when `OPENCC_DIR` points at a reference clone (the text side has
 * no other source since the pilot `.txt` resources were replaced by `.sok`).
 */
class DictionaryDecodeComparisonTest {

    @Test
    fun recordsTextVsSokDecodeBaseline() {
        val openccDir = System.getenv("OPENCC_DIR")?.let(::File) ?: return
        val loader = object {}.javaClass.classLoader ?: return

        for (name in listOf("STCharacters", "TSCharacters", "STPhrases")) {
            val textBytes = openccDir.resolve("data/dictionary/$name.txt").readBytes()
            val sokBytes = loader.getResourceAsStream("dictionary/$name.sok")
                ?.use { it.readBytes() }
                ?: error("packaged dictionary/$name.sok missing")

            val textMillis = medianMillis(REPETITIONS) { TextDictionaryFormat.decode(textBytes) }
            val sokMillis = medianMillis(REPETITIONS) { SokDictionaryFormat.decode(sokBytes) }
            val textHeapKiB = retainedHeapKiB { TextDictionaryFormat.decode(textBytes) }
            val sokHeapKiB = retainedHeapKiB { SokDictionaryFormat.decode(sokBytes) }

            println(
                "[sok-baseline] $name entries≈${entryCountOf(textBytes)} " +
                        "textBytes=${textBytes.size} sokBytes=${sokBytes.size} " +
                        "textDecode=${textMillis}ms sokDecode=${sokMillis}ms " +
                        "textHeap≈${textHeapKiB}KiB sokHeap≈${sokHeapKiB}KiB",
            )
        }
    }

    /** Wall-clock median of [runs] fresh decodes; the action's result is dropped. */
    private fun medianMillis(runs: Int, action: () -> Any): Long {
        val samples = (1..runs).map {
            val start = System.nanoTime()
            action()
            (System.nanoTime() - start) / 1_000_000
        }.sorted()
        return samples[runs / 2]
    }

    /** Best-effort retained-heap estimate: delta around a GC-prompted baseline. */
    private fun retainedHeapKiB(action: () -> Any): Long {
        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val kept = action()
        System.gc()
        val after = runtime.totalMemory() - runtime.freeMemory()
        // Keep the instance strongly live until after the second GC.
        kept.hashCode()
        return (after - before) / 1024
    }

    /** Entry count per the text format's own accounting (`key\tvalues` lines). */
    private fun entryCountOf(textBytes: ByteArray): Int =
        textBytes.decodeToString().count { it == '\n' }

    private companion object {
        const val REPETITIONS: Int = 7
    }
}
