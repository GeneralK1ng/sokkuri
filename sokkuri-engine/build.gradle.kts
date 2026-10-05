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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.vanniktechMavenPublish)
}

kotlin {
    explicitApi()

    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.generalk1ng.sokkuri.engine"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":sokkuri-api"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }

    // The Dictionary contract suite lives in src/contractTest (outside
    // commonTest) so sokkuri-resource's tests can compile the very same file
    // via srcDir — one suite, zero copies, retrieval cannot drift between
    // backends (docs/milestones/m1-dictgen-sok.md, steps 1.2 / 2.4).
    sourceSets.commonTest.get().kotlin.srcDir("src/contractTest/kotlin")
}
