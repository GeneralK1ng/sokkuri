package com.generalk1ng.sokkuri.benchmark

/**
 * The §3.1 timing methodology, shared by every subcommand that measures
 * ops: warmup, per-op sampling, and percentile summarization. Keeping the
 * math pure ([summarize]) apart from the measurement loop ([measure]) makes
 * the percentiles exactly testable and the sampling swappable (the JMH
 * upgrade path, m2-benchmark.md §7, replaces only [measure]).
 */
internal object Timing {

    /** Summarized per-op latencies; all values in microseconds per op. */
    internal data class Result(
        /** Number of measured (post-warmup) iterations. */
        val iterations: Int,
        val minUs: Double,
        val medianUs: Double,
        val p90Us: Double,
    )

    /**
     * Warmup size per §3.1: `max(200, iterations / 10)` — enough for JIT
     * stability at small iteration counts without dominating the run.
     */
    internal fun warmupIterations(iterations: Int): Int = maxOf(200, iterations / 10)

    /**
     * Measures [op]: [warmupIterations] untimed calls, then [iterations]
     * timed calls with per-op `System.nanoTime()` sampling.
     */
    internal fun measure(iterations: Int, op: () -> Unit): Result {
        val warmup = warmupIterations(iterations)
        repeat(warmup) { op() }
        val samples = LongArray(iterations)
        for (i in 0 until iterations) {
            val start = System.nanoTime()
            op()
            samples[i] = System.nanoTime() - start
        }
        return summarize(samples, iterations)
    }

    /**
     * Reduces raw per-op nanos to [Result]. Pure: [measure]'s only
     * non-deterministic part stays outside.
     *
     * Percentiles use nearest-rank on the sorted samples; [iterations] is
     * carried through because it is a property of the run, not of the data.
     */
    internal fun summarize(samples: LongArray, iterations: Int): Result {
        val sorted = samples.sorted()
        fun rank(p: Double): Long = sorted[minOf(sorted.size - 1, ((sorted.size - 1) * p).toInt())]
        return Result(
            iterations = iterations,
            minUs = sorted.first() / 1_000.0,
            medianUs = rank(0.50) / 1_000.0,
            p90Us = rank(0.90) / 1_000.0,
        )
    }
}
