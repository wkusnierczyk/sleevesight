plugins {
    id("sleevesight.pipeline")
    `java-test-fixtures`
    id("sleevesight.mutation")
}

// Mutation testing (`sleevesight.mutation`, `design/mutation-testing.md`) of the code that holds,
// writes, reads, seals and opens the API keys' setup codes: the portable code and its JVM crypto.
pitest {
    mainSourceSets = listOf(sourceSets.main.get(), sourceSets.named("jvmMain").get())
    targetClasses =
        listOf(
            "app.sleevesight.core.Secret",
            "app.sleevesight.core.Secret\$*",
            "app.sleevesight.core.keys.*",
            "app.sleevesight.core.platform.JvmKeyCrypto",
        )
    targetTests = listOf("app.sleevesight.core.SecretTest", "app.sleevesight.core.keys.*")
}

// The shared fixtures (`fixtures/` at the root) are resources of the test-fixtures source set, so
// any JVM module reads them with `testImplementation(testFixtures(project(":core")))`.
sourceSets {
    named("testFixtures") {
        resources.srcDir(rootProject.layout.projectDirectory.dir("fixtures"))
    }
}

dependencies {
    // `SourceAdapter.lookup` takes a `Deferred`, so coroutines are part of the API.
    api(libs.kotlinx.coroutines.core)
    // The genre vocabulary is a JSON resource (`GenreVocabulary`).
    implementation(libs.kotlinx.serialization.json)
    testFixturesApi(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
