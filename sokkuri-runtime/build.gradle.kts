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

// ---------------------------------------------------------------------------
// Apple resource wiring.
//
// Kotlin/Native processes src/commonMain/resources into
// build/processedResources/<target>/main, but neither embeds them into the
// produced frameworks nor places them beside test executables. Copy them
// explicitly (docs/milestones/m1-dictgen-sok.md, step 0.1): into framework
// bundles for consumers (read via NSBundle), and beside test binaries for
// the simulator test runner (a bare binary's NSBundle.mainBundle resolves
// to its containing directory).
// ---------------------------------------------------------------------------
val appleProcessedResources = layout.buildDirectory.dir("processedResources")

kotlin.targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
    val nativeTarget = this
    val mainResources = appleProcessedResources.map { it.dir("${nativeTarget.name}/main") }

    // The Copy tasks below read the ProcessResources output directory, which
    // carries no task-dependency information through the directory provider;
    // wire the dependency explicitly for every copy task.
    val processResources = tasks.matching { it.name == nativeTarget.name + "ProcessResources" }

    binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.Framework>().configureEach {
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

    binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
        val testBinary = this
        val copyResources = tasks.register<Copy>(
            "copyResourcesBeside" + testBinary.name.replaceFirstChar { it.uppercase() } +
                nativeTarget.name.replaceFirstChar { it.uppercase() },
        ) {
            from(mainResources)
            into(testBinary.outputFile.parentFile)
            dependsOn(processResources)
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
