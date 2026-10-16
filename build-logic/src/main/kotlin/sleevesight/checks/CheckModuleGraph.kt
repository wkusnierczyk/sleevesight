package sleevesight.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Properties

/**
 * Enforces the module boundaries of the architecture, from `config/module-graph.properties`:
 *
 * - each module may depend directly only on the modules listed for it, on any classpath;
 * - a module listed under `compile-forbidden.<module>` may not be on that module's compile
 *   classpath at all, even through another module (an `api` dependency), though it may be on its
 *   runtime classpath: the direct-dependency rule alone would not see it;
 * - an HTTP client (`network-groups`) may be reached only through a module in `network-modules`:
 *   a client reachable from this module on a path that avoids those modules fails the check,
 *   whether it is a direct dependency or arrives through a third-party library.
 */
abstract class CheckModuleGraph : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rules: RegularFileProperty

    @get:Input
    abstract val modulePath: Property<String>

    /** Direct project dependencies on the runtime and compile classpaths, as project paths. */
    @get:Input
    abstract val projectDependencies: ListProperty<String>

    /** Every project on the compile classpath, directly or through another, as project paths. */
    @get:Input
    abstract val compileClasspathProjects: ListProperty<String>

    /** External modules reachable without passing through a network module, as `group:name`. */
    @get:Input
    abstract val modulesOutsideNetwork: ListProperty<String>

    /** Written on success, so the check is up to date until its inputs change. */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    init {
        marker.convention(project.layout.buildDirectory.file("checks/$name.ok"))
    }

    @TaskAction
    fun check() {
        val props = Properties().apply { rules.get().asFile.inputStream().use { load(it) } }
        fun list(key: String) = props.getProperty(key, "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

        val name = modulePath.get().removePrefix(":")
        if (props.getProperty(name) == null) {
            throw GradleException("Module :$name has no entry in ${rules.get().asFile.name}.")
        }
        val allowed = list(name).toSet()
        val networkGroups = list("network-groups")
        val networkModules = list("network-modules").toSet()

        val problems = mutableListOf<String>()
        projectDependencies.get().map { it.removePrefix(":") }.forEach { target ->
            if (target !in allowed) problems += ":$name may not depend on :$target"
        }
        problems += compileForbidden(name, list("$COMPILE_FORBIDDEN.$name"), compileClasspathProjects.get())
        if (name !in networkModules) {
            modulesOutsideNetwork.get()
                .filter { module ->
                    val group = module.substringBefore(':')
                    networkGroups.any { group == it || group.startsWith("$it.") }
                }
                .forEach { problems += ":$name reaches an HTTP client outside ${networkModules.joinToString { ":$it" }}: $it" }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Module graph violations:\n" + problems.joinToString("\n") { "  $it" })
        }
        marker.get().asFile.writeText("ok\n")
    }

    companion object {
        /** The key prefix of the modules a module may not have on its compile classpath. */
        const val COMPILE_FORBIDDEN = "compile-forbidden"

        /**
         * The problems of module [name], whose compile classpath holds the projects [onClasspath]
         * (project paths), when the modules in [forbidden] may not be among them.
         */
        fun compileForbidden(
            name: String,
            forbidden: List<String>,
            onClasspath: List<String>,
        ): List<String> {
            val present = onClasspath.map { it.removePrefix(":") }.toSet()
            return forbidden
                .filter { it in present }
                .map { ":$name may not have :$it on its compile classpath, even through another module" }
        }

        /** Every project reachable from [root], as project paths, without the root itself. */
        fun projectsReachableFrom(root: Provider<ResolvedComponentResult>): Provider<List<String>> =
            root.map { component ->
                val self = (component.id as? ProjectComponentIdentifier)?.projectPath
                CheckDependencies.reachable(component)
                    .mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }
                    .filterNot { it == self }
                    .distinct()
                    .sorted()
            }

        /** Direct project dependencies of each root, as project paths, without the root itself. */
        fun projectDependenciesOf(vararg roots: Provider<ResolvedComponentResult>): Provider<List<String>> =
            roots.map { root ->
                root.map { component ->
                    val self = (component.id as? ProjectComponentIdentifier)?.projectPath
                    component.dependencies
                        .filterIsInstance<ResolvedDependencyResult>()
                        .mapNotNull { (it.selected.id as? ProjectComponentIdentifier)?.projectPath }
                        .filterNot { it == self }
                }
            }.reduce { a, b -> a.zip(b) { x, y -> x + y } }
                .map { it.distinct().sorted() }

        /**
         * External modules reachable from [root] without passing through one of the
         * [networkModules] (project paths), as `group:name`.
         */
        fun modulesOutsideNetworkOf(
            root: Provider<ResolvedComponentResult>,
            networkModules: Set<String>,
        ): Provider<List<String>> =
            root.map { component ->
                CheckDependencies.reachable(component) { node ->
                    (node.id as? ProjectComponentIdentifier)?.projectPath in networkModules
                }
                    .mapNotNull { it.id as? ModuleComponentIdentifier }
                    .map { "${it.group}:${it.module}" }
                    .distinct()
                    .sorted()
            }
    }
}
