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
