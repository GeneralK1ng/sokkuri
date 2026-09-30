plugins {
    // No version: the Kotlin Gradle plugin is already on the build classpath
    // via org.jetbrains.kotlin.multiplatform applied by the library modules;
    // re-declaring a version here fails plugin resolution (same as
    // tools:dictgen).
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    // Dependency whitelist per m2-benchmark.md §2.1: benchmarks measure the
    // consumer path through the runtime facade and decode packaged
    // dictionaries through the resource formats. sokkuri-engine and
    // sokkuri-config stay untouched — measuring from the consumer's side is
    // the point of the module.
    implementation(project(":sokkuri-runtime"))
    implementation(project(":sokkuri-resource"))
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("com.generalk1ng.sokkuri.benchmark.BenchmarkKt")
}

tasks.test {
    useJUnitPlatform()
}

// All subcommands measure the packaged resources, so run from the repository
// root (same working-dir convention as tools:dictgen).
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
