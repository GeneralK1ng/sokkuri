package com.generalk1ng.sokkuri.benchmark

/**
 * Error type of the benchmark CLI: argument/subcommand problems terminate
 * with the message on stderr and exit code 1 (mirrors `DictgenException` —
 * one error type per tool, no exception taxonomy in a CLI this small).
 */
internal class BenchmarkException(message: String) : Exception(message)
