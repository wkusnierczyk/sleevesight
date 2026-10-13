// A pipeline module (`:core`, `:sources`, `:ai`, `:store`, `:net`): a plain Kotlin JVM module kept
// Multiplatform-ready (architecture, decision D2). Detekt adds the readiness rules. JSON goes
// through kotlinx.serialization, so its compiler plugin is applied here.
plugins {
    id("sleevesight.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

detekt {
    config.setFrom(
        rootProject.file("config/detekt/detekt.yml"),
        rootProject.file("config/detekt/pipeline.yml"),
    )
}

// The readiness rules need type resolution, which the plain `detekt` task in `check` does not do.
tasks.named("check") {
    dependsOn("detektMain", "detektTest")
}

// A module with shared test fixtures (`java-test-fixtures`) checks them the same way.
pluginManager.withPlugin("java-test-fixtures") {
    tasks.named("check") {
        dependsOn("detektTestFixtures")
    }
}
