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
    // re-declaring a version here fails plugin resolution.
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    implementation(project(":sokkuri-api"))
    implementation(project(":sokkuri-config"))
    implementation(project(":sokkuri-resource"))
    // ConfigDocument is @Serializable; sokkuri-config keeps this as an
    // implementation dependency, so it must be declared here too.
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("com.generalk1ng.sokkuri.dictgen.DictgenKt")
}

tasks.test {
    useJUnitPlatform()
}

// Both entry points run with the repository root as working directory so
// the default --opencc-dir / --output-dir relative paths resolve correctly.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

/**
 * Regenerates the packaged dictionaries/configs from the OpenCC clone:
 * `:tools:dictgen:run` rewrites the resources, this task verifies the
 * committed files are in sync (fails on drift, changing nothing).
 */
tasks.register<JavaExec>("checkDictionaries") {
    group = "verification"
    description = "Fails if the packaged configs/dictionaries drift from the OpenCC sources"
    mainClass = application.mainClass
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
    args = listOf("--check")
}
