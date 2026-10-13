// A pipeline module (`:core`, `:sources`, `:ai`, `:store`, `:net`): a plain Kotlin JVM module kept
// Multiplatform-ready (architecture, decision D2). Detekt adds the readiness rules. JSON goes
// through kotlinx.serialization, so its compiler plugin is applied here.
import sleevesight.checks.CheckPlatformSources

plugins {
    id("sleevesight.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Platform implementations of the small interfaces the portable code declares (accent folding,
// hashing) live in `src/jvmMain/kotlin`, compiled with `main`. The directory is where a
// Multiplatform `jvmMain` would be, and the readiness rules exempt it. `checkPlatformSources`
// keeps the rest: portable code may not refer to it, and it may not import the network APIs.
kotlin.sourceSets.named("main") {
    kotlin.srcDir("src/jvmMain/kotlin")
}

val checkPlatformSources =
    tasks.register<CheckPlatformSources>("checkPlatformSources") {
        group = "verification"
        description = "Fails when portable code refers to platform code, or platform code uses network APIs."
        portableSources.from(layout.projectDirectory.dir("src/main/kotlin"))
        platformSources.from(layout.projectDirectory.dir("src/jvmMain/kotlin"))
        // The same prefixes as the network `ForbiddenImport` entries in `config/detekt/detekt.yml`;
        // a change to one list is made to the other. `:net`, whose engine factory needs OkHttp and
        // Ktor, overrides this with a shorter list.
        forbiddenPlatformNames.convention(listOf("java.net.", "javax.net.", "okhttp3.", "io.ktor.client."))
    }

detekt {
    config.setFrom(
        rootProject.file("config/detekt/detekt.yml"),
        rootProject.file("config/detekt/pipeline.yml"),
    )
}

// The readiness rules need type resolution, which the plain `detekt` task in `check` does not do.
tasks.named("check") {
    dependsOn("detektMain", "detektTest", checkPlatformSources)
}

// A module with shared test fixtures (`java-test-fixtures`) checks them the same way.
pluginManager.withPlugin("java-test-fixtures") {
    tasks.named("check") {
        dependsOn("detektTestFixtures")
    }
}
