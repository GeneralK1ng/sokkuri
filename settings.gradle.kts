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

rootProject.name = "sokkuri"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Layered module structure; dependency direction is strictly downward:
//   :sokkuri-runtime -> :sokkuri-resource -> :sokkuri-config -> :sokkuri-engine -> :sokkuri-api
// Future build tooling (dictionary compiler) lands under tools/ as a regular
// module of THIS build (include(":tools:dictgen")), not part of the published
// family. It must live in this build, not as a composite includeBuild: an
// included build cannot depend on the main build's projects, and dictgen
// needs to share the .sok format codec with :sokkuri-resource.
include(":sokkuri-runtime")
include(":sokkuri-api")
include(":sokkuri-engine")
include(":sokkuri-config")
include(":sokkuri-resource")
// Dictionary compiler: same-build module under tools/ (see the pinned note
// above about why this is include() and not includeBuild). Not part of the
// published family; JVM-only application.
include(":tools:dictgen")
// Benchmark harness: same-build module under tools/ (it must depend on this
// build's :sokkuri-runtime/:sokkuri-resource). Not published; JVM-only
// application, deliberately not wired into check (m2-benchmark.md §2.1).
include(":tools:benchmark")
