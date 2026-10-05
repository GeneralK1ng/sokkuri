/*
 * Copyright 2026 The Sokkuri Authors and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.generalk1ng.sokkuri.benchmark

/**
 * Error type of the benchmark CLI: argument/subcommand problems terminate
 * with the message on stderr and exit code 1 (mirrors `DictgenException` —
 * one error type per tool, no exception taxonomy in a CLI this small).
 */
internal class BenchmarkException(message: String) : Exception(message)
