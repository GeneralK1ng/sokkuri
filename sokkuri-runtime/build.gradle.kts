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

import org.jetbrains.kotlin.gradle.ComposeKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.extraProperties
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.plugin.mpp.resources.KotlinTargetResourcesPublication

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.vanniktechMavenPublish)
}

kotlin {
    explicitApi()

    jvm()

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Sokkuri"
            isStatic = true
        }
    }

    android {
        namespace = "com.generalk1ng.sokkuri"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }

        withHostTest {}
    }

    sourceSets {
        // Shared JVM/Android sources (classpath resource loading, host tests).
        val jvmAndroidMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmAndroidMain)
        androidMain.get().dependsOn(jvmAndroidMain)

        // Shared Apple sources. The default hierarchy template does not fire
        // in this module (custom source sets above), so wire iosMain manually.
        val iosMain by creating {
            dependsOn(commonMain.get())
        }
        iosArm64Main.get().dependsOn(iosMain)
        iosSimulatorArm64Main.get().dependsOn(iosMain)

        commonMain.dependencies {
            api(project(":sokkuri-api"))
            implementation(project(":sokkuri-engine"))
            implementation(project(":sokkuri-config"))
            implementation(project(":sokkuri-resource"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // Golden harness parses testcases.json with the same lenient
            // JSONC policy as configs (JsonSupport) plus JsonElement walking.
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// ---------------------------------------------------------------------------
// Apple resource wiring.
//
// Kotlin/Native processes src/commonMain/resources into
// build/processedResources/<target>/main, but neither embeds them into the
// produced frameworks nor places them beside test executables. Copy them
// explicitly: into framework
// bundles for consumers (read via NSBundle), and beside test binaries for
// the simulator test runner (a bare binary's NSBundle.mainBundle resolves
// to its containing directory).
// ---------------------------------------------------------------------------
val appleProcessedResources = layout.buildDirectory.dir("processedResources")
kotlin.targets.withType<KotlinNativeTarget>().configureEach {
    val nativeTarget = this
    val mainResources = appleProcessedResources.map { it.dir("${nativeTarget.name}/main") }
    val testResources = appleProcessedResources.map { it.dir("${nativeTarget.name}/test") }

    // The Copy tasks below read the ProcessResources output directory, which
    // carries no task-dependency information through the directory provider;
    // wire the dependency explicitly for every copy task.
    val processResources = tasks.matching { it.name == nativeTarget.name + "ProcessResources" }
    val processTestResources = tasks.matching { it.name == nativeTarget.name + "TestProcessResources" }

    binaries.withType<Framework>().configureEach {
        val framework = this
        val copyResources = tasks.register<Copy>(
            "copyResourcesInto" + framework.name.replaceFirstChar { it.uppercase() } +
                nativeTarget.name.replaceFirstChar { it.uppercase() },
        ) {
            from(mainResources)
            into(framework.outputFile)
            // TaskCollection is Buildable: dependsOn resolves the matching
            // ProcessResources task lazily at execution, with no reentrant
            // configure-on-task-set calls.
            dependsOn(processResources)
        }
        linkTaskProvider.configure { finalizedBy(copyResources) }
    }

    binaries.withType<TestExecutable>().configureEach {
        val testBinary = this
        val copyResources = tasks.register<Copy>(
            "copyResourcesBeside" + testBinary.name.replaceFirstChar { it.uppercase() } +
                nativeTarget.name.replaceFirstChar { it.uppercase() },
        ) {
            description = "Copy the test resources beside the test binary for simulator/device execution"
            from(mainResources)
            from(testResources)
            into(testBinary.outputFile.parentFile)
            dependsOn(processResources)
            dependsOn(processTestResources)
        }
        linkTaskProvider.configure { finalizedBy(copyResources) }
        // The simulator/device test task consumes the binary's directory as
        // an input; Gradle requires the resource copy to be an explicit
        // dependency (finalizedBy alone is ordering, not dependency).
        // Device targets have no test task — match by name instead of named().
        tasks.matching { it.name == nativeTarget.name + "Test" }
            .configureEach { dependsOn(copyResources) }
    }
}

// Dictionary data (compiled .sok lexicons + rewritten config JSON) is
// generated into this module's resources by the future tools/dictgen
// build tooling; see the design documentation.

// ---------------------------------------------------------------------------
// Klib resource variants.
//
// Kotlin 2.4 removed the old "auto-embed commonMain resources into klibs"
// behavior: the packaged dictionaries no longer travel inside the published
// .klib. The replacement mechanism publishes them as a kotlin_resources.zip
// Gradle variant per Apple target; consumers resolve the variant and place
// the files into their app/framework bundle (the iOS loader reads bundle
// files via NSBundle + POSIX stdio, same as in this build). This wiring
// mirrors the reference usage in the Compose Multiplatform Gradle plugin
// (configureKmpResources in MultimoduleResources.kt). Consumers applying the
// Compose plugin get both sides automatically; plain-KMP consumers add the
// resolveResources snippet documented in the README.
// ---------------------------------------------------------------------------
@OptIn(ComposeKotlinGradlePluginApi::class)
private fun Project.publishDictionariesAsKlibResourceVariants() {
    val kotlinExtension = extensions.getByType(KotlinMultiplatformExtension::class.java)
    val kmpResources = extraProperties.get(KotlinTargetResourcesPublication.EXTENSION_NAME)
            as KotlinTargetResourcesPublication
    val commonMainResources = provider { file("src/commonMain/resources") }
    val emptyResources = layout.buildDirectory
        .dir("kotlin-multiplatform-resources/emptyResourcesDir")
        .map { it.asFile }

    kotlinExtension.targets.withType(KotlinNativeTarget::class.java).configureEach {
        kmpResources.publishResourcesAsKotlinComponent(
            this,
            resourcePathForSourceSet = { sourceSet ->
                if (sourceSet.name == KotlinSourceSet.COMMON_MAIN_SOURCE_SET_NAME) {
                    KotlinTargetResourcesPublication.ResourceRoot(commonMainResources, emptyList(), emptyList())
                } else {
                    // Intermediate source sets (iosMain, iosArm64Main, ...)
                    // carry no resources; an empty root keeps the hierarchy
                    // assembly from failing on missing directories.
                    KotlinTargetResourcesPublication.ResourceRoot(emptyResources, emptyList(), emptyList())
                }
            },
            relativeResourcePlacement = provider { File("") },
        )
    }
}

publishDictionariesAsKlibResourceVariants()