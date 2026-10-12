// A pipeline module (`:core`, `:sources`, `:ai`, `:store`, `:net`): a plain Kotlin JVM module kept
// Multiplatform-ready (architecture, decision D2). Detekt adds the readiness rules.
plugins {
    id("sleevesight.kotlin-jvm")
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
