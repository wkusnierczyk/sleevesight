package sleevesight.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Fails when `jvmMain`'s classpaths resolve different artifacts from `main`'s: its compile
 * classpath must resolve exactly the artifacts of `main`'s, and its runtime classpath those of
 * `main`'s, apart from `main`'s own classes, which `jvmMain` compiles against. Each artifact is
 * its component and file name, so declarations, inherited configurations, constraints and
 * platforms, project and file dependencies, classifiers and variants all count. `jvmMain` ships in
 * `main`'s jar and its consumers get only `main`'s dependencies, which the dependency allowlist
 * and the module graph check cover; with equal artifacts, `jvmMain` also uses the versions `main`
 * has locked. A graph that does not resolve fails first, with its own message.
 */
abstract class CheckJvmMainDependencies : DefaultTask() {
    /** `configuration: requested: failure`, for each unresolved dependency of the four graphs. */
    @get:Input
    abstract val unresolved: ListProperty<String>

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
        val failures = unresolved.get()
        if (failures.isNotEmpty()) {
            throw GradleException(
                "Dependencies that do not resolve; fix them, or, where a lockfile is out of date, run " +
                    "`./gradlew resolveAndLockAll --write-locks`:\n" + failures.joinToString("\n") { "  $it" },
            )
        }
        val problems =
            differences("compile", mainCompile.get(), jvmMainCompile.get()) +
                differences("runtime", mainRuntime.get(), jvmMainRuntime.get())
        if (problems.isNotEmpty()) {
            throw GradleException("jvmMain shares main's dependencies:\n" + problems.joinToString("\n") { "  $it" })
        }
        marker.get().asFile.writeText("ok\n")
    }

    private fun differences(
        classpath: String,
        main: List<String>,
        jvmMain: List<String>,
    ): List<String> =
        (jvmMain - main.toSet()).map {
            "$it is on jvmMain's $classpath classpath only; declare it on main's configurations"
        } +
            (main - jvmMain.toSet()).map {
                "$it is on main's $classpath classpath only; look for an exclude or a constraint on a jvmMain configuration"
            }

    companion object {
        /** Each artifact [configuration] resolves, as `component file`, sorted, without [own] files. */
        fun artifactsOf(
            configuration: Provider<Configuration>,
            own: FileCollection,
        ): Provider<List<String>> =
            configuration
                .flatMap { it.incoming.artifactView { lenient(true) }.artifacts.resolvedArtifacts }
                .map { artifacts ->
                    val ownFiles = own.files
                    artifacts.filterNot { it.file in ownFiles }.map(::identity).distinct().sorted()
                }

        /** `configuration: requested: failure` for each unresolved dependency [configuration] has. */
        fun unresolvedOf(configuration: Provider<Configuration>): Provider<List<String>> =
            configuration.flatMap { conf ->
                conf.incoming.resolutionResult.rootComponent.map { root ->
                    CheckDependencies.reachable(root)
                        .flatMap { component ->
                            component.dependencies.filterIsInstance<UnresolvedDependencyResult>().map {
                                "${conf.name}: ${it.requested.displayName}: ${it.failure.message}"
                            }
                        }.distinct()
                }
            }

        private fun identity(artifact: ResolvedArtifactResult): String =
            "${artifact.id.componentIdentifier.displayName} ${artifact.file.name}"
    }
}
