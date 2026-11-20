// Mutation testing (Pitest) of the code that stores, parses and moves the users' API keys
// (`design/mutation-testing.md`): the module names its classes in `pitest { targetClasses }`, and
// `./gradlew pitest` writes the report to `build/reports/pitest/`. Not part of `check`: it is slow,
// and `.github/workflows/mutation.yml` runs it nightly and by hand.
import info.solidsoft.gradle.pitest.PitestPluginExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("info.solidsoft.pitest")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

// By type: the plugin adds its extension only once the Java plugin is applied, so this script gets no
// `pitest` accessor.
configure<PitestPluginExtension> {
    pitestVersion = catalog.findVersion("pitest").get().requiredVersion
    // The tests run on JUnit 5 (`sleevesight.kotlin-jvm`).
    junit5PluginVersion = catalog.findVersion("pitest-junit5").get().requiredVersion
    outputFormats = listOf("HTML", "XML")
    // One report path, for the workflow to upload.
    timestampedReports = false
    threads = 2
    // Kotlin's own checks (`Intrinsics.checkNotNull…`) and the code the compiler adds for a
    // `when` over an enum are not the module's behaviour.
    avoidCallsTo = listOf("kotlin.jvm.internal")
    excludedClasses = listOf("*\$WhenMappings")
    // The run fails rather than report on nothing, when a renamed class leaves a target matching none.
    failWhenNoMutations = true
}
