package sleevesight.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Fails when `jvmMain` resolves different external modules from `main`: its compile classpath
 * must hold exactly the modules and versions of `main`'s, and its runtime classpath those of
 * `main`'s. `jvmMain` ships in `main`'s jar and its consumers see only `main`'s dependencies, which
 * the dependency allowlist, the module graph check and locking cover; a dependency or a version
 * that only `jvmMain` had would escape all of them. Comparing what resolves covers every route:
 * direct declarations, inherited configurations, constraints and platforms.
 */
abstract class CheckJvmMainDependencies : DefaultTask() {
    @get:Input
    abstract val mainCompile: ListProperty<String>

    @get:Input
    abstract val jvmMainCompile: ListProperty<String>

    @get:Input
    abstract val mainRuntime: ListProperty<String>

    @get:Input
    abstract val jvmMainRuntime: ListProperty<String>

    /** Written on success, so the check is up to date until its inputs change. */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    init {
        marker.convention(project.layout.buildDirectory.file("checks/$name.ok"))
    }

    @TaskAction
    fun check() {
        val problems =
            differences("compile", mainCompile.get(), jvmMainCompile.get()) +
                differences("runtime", mainRuntime.get(), jvmMainRuntime.get())
        if (problems.isNotEmpty()) {
            throw GradleException(
                "jvmMain shares main's dependencies; declare them on main's configurations:\n" +
                    problems.joinToString("\n") { "  $it" },
            )
        }
        marker.get().asFile.writeText("ok\n")
    }

    private fun differences(
        classpath: String,
        main: List<String>,
        jvmMain: List<String>,
    ): List<String> =
        (jvmMain - main.toSet()).map { "$it is on jvmMain's $classpath classpath, not main's" } +
            (main - jvmMain.toSet()).map { "$it is on main's $classpath classpath, not jvmMain's" }
}
