package sleevesight.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a module on the shipped runtime classpath belongs to a group that is not in the
 * dependency allowlist. A new dependency is a reviewed edit to that file, which also records
 * the licence of each group, so analytics, crash-reporting and ads SDKs cannot arrive unseen.
 */
abstract class CheckDependencies : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allowlist: RegularFileProperty

    @get:Input
    abstract val modules: ListProperty<String>

    @TaskAction
    fun check() {
        val allowed =
            allowlist.get().asFile.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .map { it.split(Regex("\\s+")).first() }
        val unknown =
            modules.get().filter { module ->
                val group = module.substringBefore(':')
                allowed.none { prefix -> group == prefix || group.startsWith("$prefix.") }
            }
        if (unknown.isNotEmpty()) {
            throw GradleException(
                "Dependencies outside ${allowlist.get().asFile.name}:\n" +
                    unknown.joinToString("\n") { "  $it" } +
                    "\nAdd each group, with its licence, after review.",
            )
        }
    }

    companion object {
        /** Every external module reachable from [root], as `group:name:version`, sorted. */
        fun modulesOf(root: Provider<ResolvedComponentResult>): Provider<List<String>> =
            root.map { component ->
                val seen = mutableSetOf<String>()
                val queue = ArrayDeque(listOf(component))
                val visited = mutableSetOf<ResolvedComponentResult>()
                while (queue.isNotEmpty()) {
                    val next = queue.removeFirst()
                    if (!visited.add(next)) continue
                    (next.id as? ModuleComponentIdentifier)?.let { seen += "${it.group}:${it.module}:${it.version}" }
                    next.dependencies
                        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                        .forEach { queue += it.selected }
                }
                seen.sorted()
            }
    }
}
