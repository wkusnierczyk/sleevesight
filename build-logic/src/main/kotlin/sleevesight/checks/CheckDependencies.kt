package sleevesight.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a module on the shipped runtime classpath belongs to a group that is not in the
 * dependency allowlist. A new dependency is a reviewed edit to that file, which also records
 * the licence of each group, so analytics, crash-reporting and ads SDKs cannot arrive unseen.
 *
 * An allowlist entry matches its group exactly; `group.*` matches its subgroups as well.
 */
abstract class CheckDependencies : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allowlist: RegularFileProperty

    @get:Input
    abstract val modules: ListProperty<String>

    /** Written on success, so the check is up to date until its inputs change. */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    init {
        marker.convention(project.layout.buildDirectory.file("checks/$name.ok"))
    }

    @TaskAction
    fun check() {
        val entries =
            allowlist.get().asFile.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .map { it.split(Regex("\\s+")).first() }
        val unknown =
            modules.get().filter { module ->
                val group = module.substringBefore(':')
                entries.none { entry -> groupMatches(entry, group) }
            }
        if (unknown.isNotEmpty()) {
            throw GradleException(
                "Dependencies outside ${allowlist.get().asFile.name}:\n" +
                    unknown.joinToString("\n") { "  $it" } +
                    "\nAdd each group, with its licence, after review.",
            )
        }
        marker.get().asFile.writeText("ok\n")
    }

    companion object {
        /** `entry` matches `group` exactly, or, written `prefix.*`, matches `prefix` and its subgroups. */
        fun groupMatches(
            entry: String,
            group: String,
        ): Boolean =
            if (entry.endsWith(".*")) {
                val prefix = entry.removeSuffix(".*")
                group == prefix || group.startsWith("$prefix.")
            } else {
                group == entry
            }

        /** Every external module reachable from [root], as `group:name:version`, sorted. */
        fun modulesOf(root: Provider<ResolvedComponentResult>): Provider<List<String>> =
            root.map { component ->
                reachable(component)
                    .mapNotNull { it.id as? ModuleComponentIdentifier }
                    .map { "${it.group}:${it.module}:${it.version}" }
                    .distinct()
                    .sorted()
            }

        /** Every component reachable from [root], not descending into [stopAt]. */
        fun reachable(
            root: ResolvedComponentResult,
            stopAt: (ResolvedComponentResult) -> Boolean = { false },
        ): List<ResolvedComponentResult> {
            val visited = linkedSetOf<ResolvedComponentResult>()
            val queue = ArrayDeque(listOf(root))
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (!visited.add(next) || (next != root && stopAt(next))) continue
                next.dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .forEach { queue += it.selected }
            }
            return visited.toList()
        }
    }
}
