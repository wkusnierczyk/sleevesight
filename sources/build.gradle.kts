plugins {
    id("sleevesight.pipeline")
    // The replay `NetClient` the adapter tests share (`ReplayNetClient`), reusable by `:cli`.
    `java-test-fixtures`
}

dependencies {
    implementation(project(":core"))
    implementation(project(":net"))
    implementation(libs.kotlinx.serialization.json)

    testFixturesApi(project(":net"))
    // The shared fixtures (`fixtures/`), recorded responses included, are resources of `:core`'s test fixtures.
    testFixturesApi(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)
}
