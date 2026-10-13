plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    // Reads class files for `checkNetworkReferences`. BSD-3-Clause; build-time only, so outside
    // the app's dependency allowlist.
    implementation(libs.asm.commons)

    testImplementation(libs.kotlin.test)
    testImplementation(gradleTestKit())
}

tasks.test {
    useJUnitPlatform()
}
