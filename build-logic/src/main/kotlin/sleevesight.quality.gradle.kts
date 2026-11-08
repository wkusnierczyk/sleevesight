// Static analysis for every module: Detekt and ktlint, configured from the root `config/`.
import org.gradle.api.artifacts.VersionCatalogsExtension
import sleevesight.checks.CheckRegexBraces

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

// Regexes Android cannot compile, which the JVM's tests pass (#1113): a brace not escaped, outside a
// character class and not a quantifier. Read from the sources of every source set but the tests'.
val checkRegexBraces =
    tasks.register<CheckRegexBraces>("checkRegexBraces") {
        group = "verification"
        description = "Fails when a regex in the main sources has a brace that Android's ICU regex engine rejects."
        sources.from(
            fileTree("src") {
                include("*/kotlin/**/*.kt", "*/java/**/*.kt")
                exclude("test*/**", "androidTest*/**", "functionalTest/**")
            },
        )
    }

// `check` comes with the module's Kotlin or Android plugin, which may be applied after this one.
tasks.matching { it.name == "check" }.configureEach {
    dependsOn(checkRegexBraces)
}

// The static checks of `check` without the tests, for the pre-push hook (`.githooks/pre-push`):
// ktlint, Detekt and `checkRegexBraces` here; the pipeline modules add their type-resolved Detekt
// tasks and the app its Android lint.
tasks.register("staticChecks") {
    group = "verification"
    description =
        "Runs ktlint, Detekt, the regex braces check and (in the app) Android lint, as `check` does, " +
            "without the tests."
    dependsOn("ktlintCheck", "detekt", checkRegexBraces)
}
