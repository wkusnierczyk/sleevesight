package sleevesight.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when the dependencies page (`docs/dependencies/index.md`, #412) and the shipped runtime
 * classpath disagree: a Maven group on the classpath that the page does not name, or a group the
 * page names that is no longer on it. So a dependency added or removed without the page fails.
 *
 * The page names its groups on list items that start `**Groups:**`, each group in backticks after
 * it; those count, and nothing else on the page does, so groups named in prose or under the
 * build-time tools are not read. The check reads groups only, not modules, versions or licences.
 */
abstract class CheckDependencyPage : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val page: RegularFileProperty

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
        val file = page.get().asFile
        val found = problems(file.readText(), modules.get())
        if (found.isNotEmpty()) {
            throw GradleException(
                "${file.name} (docs/dependencies/) disagrees with the shipped runtime classpath:\n" +
                    found.joinToString("\n") { "  $it" } +
                    "\nList each shipped group on a `- **Groups:**` line, with its use and licence.",
            )
        }
        marker.get().asFile.writeText("ok\n")
    }

    companion object {
        private val GROUPS_LINE = Regex("""^\s*[-*]\s+\*\*Groups:\*\*(.*)$""")
        private val CODE = Regex("`([^`]*)`")
        private val GROUP = Regex("""[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)*""")

        /** Every backticked token on the page's `**Groups:**` lines, in order, repeats kept. */
        fun groupsOn(page: String): List<String> =
            page.lines()
                .mapNotNull { GROUPS_LINE.matchEntire(it) }
                .flatMap { line -> CODE.findAll(line.groupValues[1]).map { it.groupValues[1] }.toList() }

        /** What is wrong between [page] and [modules] (`group:name:version`), one line each; empty if nothing. */
        fun problems(
            page: String,
            modules: List<String>,
        ): List<String> {
            val listed = groupsOn(page)
            val shipped = modules.map { it.substringBefore(':') }.toSortedSet()
            val malformed = listed.filterNot { GROUP.matches(it) }.distinct()
            val groups = listed.filter { GROUP.matches(it) }
            val repeated = groups.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
            val missing = shipped.filterNot { it in groups }
            val stale = groups.distinct().filterNot { it in shipped }.sorted()
            return malformed.map { "not a Maven group: `$it`" } +
                repeated.map { "listed more than once: $it" } +
                missing.map { "shipped, not on the page: $it" } +
                stale.map { "on the page, not shipped: $it" }
        }
    }
}
