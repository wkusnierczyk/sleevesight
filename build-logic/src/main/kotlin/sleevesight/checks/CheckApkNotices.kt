package sleevesight.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

/**
 * Fails when an APK lacks its list of libraries (`assets/licences/libraries.txt`, written by
 * `listLibraryLicences`) or a notice the list names (#488), so the notices are checked where they
 * have to be, in what ships, after R8, resource shrinking and the packaging options.
 */
abstract class CheckApkNotices : DefaultTask() {
    /** The variant's APK directory (`SingleArtifact.APK`); every `.apk` in it is read. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkDirectory: DirectoryProperty

    /** Written on success, so the check is up to date until its inputs change. */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    init {
        marker.convention(project.layout.buildDirectory.file("checks/$name.ok"))
    }

    @TaskAction
    fun check() {
        val apks =
            apkDirectory.get().asFile.listFiles { file -> file.extension == "apk" }.orEmpty().sorted()
        if (apks.isEmpty()) throw GradleException("No APK in ${apkDirectory.get().asFile}")
        val problems =
            apks.flatMap { apk ->
                ZipFile(apk).use { zip ->
                    val entries = zip.entries().asSequence().map { it.name }.toSet()
                    val list = zip.getEntry(LIST)?.let { e -> zip.getInputStream(e).use { it.readBytes().decodeToString() } }
                    problems(entries, list).map { "${apk.name}: $it" }
                }
            }
        if (problems.isNotEmpty()) {
            throw GradleException("The APK's licence notices:\n" + problems.joinToString("\n") { "  $it" })
        }
        marker.get().asFile.writeText("ok\n")
    }

    companion object {
        const val FOLDER = "assets/licences/"
        const val LIST = FOLDER + ListLibraryLicences.LIST

        /** The notices the list names, in its third column. */
        fun noticesIn(list: String): List<String> =
            list.lines()
                .filterNot { it.isBlank() || it.startsWith("#") }
                .map { line -> line.split('\t').getOrElse(2) { "" } }
                .distinct()

        /** What an APK with [entries] and the list [list] (null if absent) lacks; empty if nothing. */
        fun problems(
            entries: Set<String>,
            list: String?,
        ): List<String> {
            if (list == null) return listOf("no $LIST")
            val notices = noticesIn(list)
            if (notices.isEmpty()) return listOf("$LIST lists no library")
            return notices.filterNot { it.isNotEmpty() && FOLDER + it in entries }.map { "no ${FOLDER}${it.ifEmpty { "(blank)" }}" }
        }
    }
}
