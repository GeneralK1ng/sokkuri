plugins {
    // Applied in the module build scripts; declared here so each plugin is
    // loaded once into the buildscript classloader.
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinPluginSerialization) apply false
}
