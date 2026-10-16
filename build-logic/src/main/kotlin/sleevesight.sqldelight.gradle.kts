// SQLDelight for a pipeline module (`:store`): the schemas and queries of each database in the
// source directories the module names (`src/main/sqldelight` for the caches, `src/main/history`
// for the session history), generated into the module's portable code. The generated code is SQLDelight's, so Detekt and
// ktlint leave it alone.
import io.gitlab.arturbosch.detekt.Detekt
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask

plugins {
    id("sleevesight.pipeline")
    id("app.cash.sqldelight")
}

tasks.withType<Detekt>().configureEach {
    exclude { it.file.invariantSeparatorsPath.contains("/build/generated/") }
}

tasks.withType<BaseKtLintCheckTask>().configureEach {
    exclude { it.file.invariantSeparatorsPath.contains("/build/generated/") }
}
