import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    explicitApi()

    jvm()
    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "com.generalk1ng.sokkuri.resource"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api(): DictionaryFormat.decode returns engine types and
            // ResourceDictionaryProvider implements config's DictionaryProvider
            // — both leak through this module's public signatures.
            api(project(":sokkuri-api"))
            api(project(":sokkuri-engine"))
            api(project(":sokkuri-config"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
