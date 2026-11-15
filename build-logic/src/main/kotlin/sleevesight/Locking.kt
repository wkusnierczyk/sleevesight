package sleevesight

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

/**
 * Which classpaths ship and which get the build floors (`sleevesight.locking`, #1087). Every
 * configuration is locked (#1034); these sets say only which are held to their lockfile alone.
 */
object Locking {
    /**
     * The classpaths that ship, held to each module's `gradle.lockfile` with no build floors. An
     * Android variant whose classpaths are not here fails the build (`sleevesight.android-app`), so
     * a new one never gets the floors.
     */
    val shipping =
        setOf(
            "runtimeClasspath",
            "compileClasspath",
            "releaseRuntimeClasspath",
            "releaseCompileClasspath",
            // The debug APK is the CI artifact installed on phones.
            "debugRuntimeClasspath",
            "debugCompileClasspath",
        )

    /**
     * Whether [name] gets the build floors ([BuildFloors]): every configuration but the shipping ones
     * and the module's own code beside `main` (`jvmMain*Classpath`, `testFixtures*Classpath`), which
     * resolve as `main` does.
     */
    fun isFloored(name: String): Boolean =
        name !in shipping &&
            !(name.endsWith("Classpath") && (name.startsWith("jvmMain") || name.startsWith("testFixtures")))

    /**
     * Resolves each of [configurations], its graph and its libraries' files, for `resolveAndLockAll`,
     * and returns what did not resolve: each dependency left unresolved, and each file that failed.
     * Empty when every one resolved. Files are fetched for the libraries alone: a project's are built,
     * not downloaded, and many of its variants (an app's symbols, its bundle metadata) cannot be
     * picked without the attributes only the task that reads them asks for.
     */
    fun resolveAll(configurations: Iterable<Configuration>): List<String> =
        configurations.filter { it.isCanBeResolved }.flatMap { configuration ->
            val graph = configuration.incoming.resolutionResult.allDependencies
            val unresolved =
                graph.filterIsInstance<UnresolvedDependencyResult>().map {
                    "${configuration.name}: ${it.attempted.displayName}: ${it.failure.message}"
                }
            val artifacts = configuration.incoming.artifactView {
                    lenient(true)
                    componentFilter { it is ModuleComponentIdentifier }
                }.artifacts
            artifacts.artifacts
            unresolved + artifacts.failures.map { "${configuration.name}: ${it.javaClass.simpleName}: ${it.message}" }
        }
}
