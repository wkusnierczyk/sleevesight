plugins {
    id("sleevesight.pipeline")
    `java-test-fixtures`
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

// The simulator embeds the same fixtures; a test checks that the two agree.
tasks.named<ProcessResources>("processTestResources") {
    from(rootProject.layout.projectDirectory.file("design/simulator.html")) {
        into("simulator")
    }
}
