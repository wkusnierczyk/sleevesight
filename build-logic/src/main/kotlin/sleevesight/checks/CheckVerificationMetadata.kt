package sleevesight.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a version a lockfile names has no entry in `gradle/verification-metadata.xml` (#1034).
 * Gradle accepts a file with no checksum when a key trusted for its group signed it, so a bump in such
 * a group builds with no refresh of the metadata, and its files go unpinned; this check makes the
 * refresh (`tools/refresh-dependency-verification.sh`) a condition of CI instead.
 */
abstract class CheckVerificationMetadata : DefaultTask() {
    /** Every `*gradle.lockfile` of the build: the modules', the build classpaths', the build logic's. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val lockfiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val metadata: RegularFileProperty

    /** Written on success, so the check is up to date until its inputs change. */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    init {
        marker.convention(project.layout.buildDirectory.file("checks/$name.ok"))
    }

    @TaskAction
    fun check() {
        val files = mutableListOf<Pair<String, String>>()
        lockfiles.asFileTree.visit {
            if (!isDirectory) files += relativePath.pathString to file.readText()
        }
        val missing = missing(files.sortedBy { it.first }, metadata.get().asFile.readText())
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Locked versions with no entry in gradle/verification-metadata.xml: a dependency changed " +
                    "without the metadata. Run tools/refresh-dependency-verification.sh " +
                    "(design/development.md, *Dependencies*):\n" + missing.joinToString("\n") { "  $it" },
            )
        }
        marker.get().asFile.writeText("ok\n")
    }

    companion object {
        private val component = Regex("""<component group="([^"]+)" name="([^"]+)" version="([^"]+)">""")

        /**
         * Each `group:name:version` that [lockfiles] (path to text) lock and [metadata] has no
         * `<component>` for, with the lockfiles that name it.
         */
        fun missing(
            lockfiles: List<Pair<String, String>>,
            metadata: String,
        ): List<String> {
            val recorded = component.findAll(metadata).map { it.groupValues.drop(1).joinToString(":") }.toSet()
            val lockedIn = linkedMapOf<String, MutableList<String>>()
            lockfiles.forEach { (path, text) ->
                text
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("empty=") }
                    .map { it.substringBefore('=') }
                    .forEach { lockedIn.getOrPut(it) { mutableListOf() } += path }
            }
            return lockedIn
                .filterKeys { it !in recorded }
                .toSortedMap()
                .map { (coordinates, paths) -> "$coordinates (${paths.distinct().joinToString()})" }
        }
    }
}
