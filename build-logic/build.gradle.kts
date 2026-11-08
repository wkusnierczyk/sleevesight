plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.android.gradle.plugin)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    // Compose in `:app`.
    implementation(libs.kotlin.compose.plugin)
    implementation(libs.detekt.gradle.plugin)
    // `:store`'s schema and queries (architecture, *Cache and history*).
    implementation(libs.sqldelight.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    // Reads class files for `checkNetworkReferences`. BSD-3-Clause; build-time only, so outside
    // the app's dependency allowlist.
    implementation(libs.asm.commons)

    testImplementation(libs.kotlin.test)
}

// The plugins' own libraries at no lower than the catalog's `build-floor-*` versions, the lowest with
// no known vulnerability (#1087). As constraints on `implementation`, they hold here and in each
// module's buildscript classpath, which resolves these plugins; they add no library. The modules'
// other tool classpaths (Android lint, ktlint, the tests) get the same floors from
// `sleevesight.locking` (`sleevesight.BuildFloors`).
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies.constraints {
    catalog.libraryAliases.filter { it.startsWith("build.floor.") }.forEach { alias ->
        val floor = catalog.findLibrary(alias).get().get()
        implementation("${floor.module}:${floor.versionConstraint.requiredVersion}") {
            because("The lowest version with no known vulnerability (#1087)")
        }
    }
}

// The plugins' classpath locked as the modules' shipping ones are (`sleevesight.locking`), so the
// versions stay as reviewed and OSV-Scanner reads them (`build-logic/gradle.lockfile`). The root
// build's `./gradlew resolveAndLockAll --write-locks` rewrites it too.
val lockedConfigurations = setOf("runtimeClasspath", "compileClasspath")
configurations.matching { it.name in lockedConfigurations }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}
tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("Resolves configurations at execution time to write lockfiles")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" }
    }
    doLast {
        configurations.filter { it.name in lockedConfigurations }.forEach { it.resolve() }
    }
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
    @get:Internal val dir: File,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Dtestkit.dir=${dir.path}")
}
