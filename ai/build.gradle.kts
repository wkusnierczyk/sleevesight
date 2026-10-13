plugins {
    id("sleevesight.pipeline")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":net"))
    implementation(libs.kotlinx.serialization.json)

    // The shared fixtures, recorded responses (`fixtures/http/`) included.
    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlinx.coroutines.test)
}
