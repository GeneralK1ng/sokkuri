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
    // re-declaring a version here fails plugin resolution (same as the other
    // tools modules).
    id("org.jetbrains.kotlin.jvm")
    // Decodes the harness's JSON lines (inspect results) into data classes.
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    // Addresses Sokkuri through the consumer facade and nothing else: the
    // comparison is against what a consumer would get, so the internal layers
    // stay out of reach. The one exception is the JSON reader used to pull
    // inputs out of the upstream golden file.
    implementation(project(":sokkuri-runtime"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("com.generalk1ng.sokkuri.upstreamdiff.UpstreamDiffKt")
}

tasks.test {
    useJUnitPlatform()
}

// Resolves the default --opencc-dir (and the default build dir under it)
// relative to the repository root, matching tools:dictgen and tools:benchmark.
// The oracle is built from the clone, so the run first checks that the clone
// sits unmodified at the pinned commit (:checkOpenccClone); a run pointed
// elsewhere with --opencc-dir skips that with -x checkOpenccClone.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
    dependsOn(":checkOpenccClone")
}
