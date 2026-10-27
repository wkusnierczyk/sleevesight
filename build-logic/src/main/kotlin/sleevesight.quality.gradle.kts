// Static analysis for every module: Detekt and ktlint, configured from the root `config/`.
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// The version in `gradle/libs.versions.toml`, which the pre-commit hook reads too. A build with no
// such catalog, such as the build logic's functional tests, keeps the plugin's default.
extensions
    .getByType<VersionCatalogsExtension>()
    .find("libs")
    .flatMap { it.findVersion("ktlint") }
    .ifPresent { ktlint { version = it.requiredVersion } }

// The static checks of `check` without the tests, for the pre-push hook (`.githooks/pre-push`):
// ktlint and Detekt here; the pipeline modules add their type-resolved Detekt tasks and the app its
// Android lint.
tasks.register("staticChecks") {
    group = "verification"
    description = "Runs ktlint, Detekt and (in the app) Android lint, as `check` does, without the tests."
    dependsOn("ktlintCheck", "detekt")
}
