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
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Properties

/**
 * Enforces the module boundaries of the architecture: each module may depend directly only on
 * the modules listed for it in `config/module-graph.properties`, and only the modules listed
 * under `network-modules` may depend directly on an HTTP client (`network-groups`).
 */
abstract class CheckModuleGraph : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rules: RegularFileProperty

    @get:Input
    abstract val modulePath: Property<String>

    /** Direct dependencies, as `project::path` or `module:group:name`. */
    @get:Input
    abstract val directDependencies: ListProperty<String>

    @TaskAction
    fun check() {
        val props = Properties().apply { rules.get().asFile.inputStream().use { load(it) } }
        val name = modulePath.get().removePrefix(":")
        val allowedProjects =
            props.getProperty(name)
                ?: throw GradleException("Module :$name has no entry in ${rules.get().asFile.name}.")
        val allowed = allowedProjects.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val networkGroups = props.getProperty("network-groups", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val networkModules = props.getProperty("network-modules", "").split(',').map { it.trim() }.toSet()

        val problems = mutableListOf<String>()
        directDependencies.get().forEach { dep ->
            when {
                dep.startsWith("project::") -> {
                    val target = dep.removePrefix("project::").removePrefix(":")
                    if (target !in allowed) problems += ":$name may not depend on :$target"
                }
                dep.startsWith("module:") -> {
                    val group = dep.removePrefix("module:").substringBefore(':')
                    val isNetwork = networkGroups.any { group == it || group.startsWith("$it.") }
                    if (isNetwork && name !in networkModules) {
                        problems += ":$name may not depend on an HTTP client (${dep.removePrefix("module:")})"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Module graph violations:\n" + problems.joinToString("\n") { "  $it" })
        }
    }

    companion object {
        fun directOf(root: Provider<ResolvedComponentResult>): Provider<List<String>> =
            root.map { component ->
                component.dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .mapNotNull { dep ->
                        when (val id = dep.selected.id) {
                            is ProjectComponentIdentifier -> "project::${id.projectPath}"
                            is ModuleComponentIdentifier -> "module:${id.group}:${id.module}"
                            else -> null
                        }
                    }
                    .filterNot { it == "project::${(component.id as? ProjectComponentIdentifier)?.projectPath}" }
                    .distinct()
                    .sorted()
            }
    }
}
