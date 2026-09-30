import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
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
        }
    }
}

// Dictionary data (compiled .sok lexicons + rewritten config JSON) is
// generated into this module's resources by the future tools/dictgen
// build tooling; see the design documentation.
