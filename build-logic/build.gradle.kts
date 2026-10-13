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
}

tasks.test {
    useJUnitPlatform()
}

// Functional tests run the convention plugins in nested builds with TestKit. Those resolve Kotlin
// from Maven Central, so they stay out of `check`, keeping `./gradlew build` fast and able to run
// offline; CI runs `./gradlew :build-logic:functionalTest` as a step of its own. The TestKit home
// stays under `build/`, so local reruns are warm.
val functionalTest = sourceSets.create("functionalTest")
kotlin.target.compilations.named("functionalTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
}
configurations.named("functionalTestImplementation") {
    extendsFrom(configurations.testImplementation.get())
}
dependencies {
    "functionalTestImplementation"(gradleTestKit())
}
gradlePlugin {
    testSourceSets(functionalTest)
}

tasks.register<Test>("functionalTest") {
    description = "Runs the convention plugins in nested TestKit builds; needs Maven Central."
    group = "verification"
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
    jvmArgumentProviders += TestKitDir(layout.buildDirectory.dir("testkit").get().asFile)
}

// The TestKit home as a system property, kept out of the task's inputs so the cache key does not
// depend on where the checkout is.
class TestKitDir(
    @get:LocalState val dir: File,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dtestkit.dir=${dir.path}")
}
