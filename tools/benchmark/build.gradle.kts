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

plugins {
    // No version: the Kotlin Gradle plugin is already on the build classpath
    // via org.jetbrains.kotlin.multiplatform applied by the library modules;
    // re-declaring a version here fails plugin resolution (same as
    // tools:dictgen).
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    // Dependency whitelist: benchmarks measure the
    // consumer path through the runtime facade and decode packaged
    // dictionaries through the resource formats. sokkuri-engine and
    // sokkuri-config stay untouched — measuring from the consumer's side is
    // the point of the module.
    implementation(project(":sokkuri-runtime"))
    implementation(project(":sokkuri-resource"))
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("com.generalk1ng.sokkuri.benchmark.BenchmarkKt")
}

tasks.test {
    useJUnitPlatform()
}

// All subcommands measure the packaged resources, so run from the repository
// root (same working-dir convention as tools:dictgen).
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
