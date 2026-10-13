package sleevesight.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Keeps the split between a pipeline module's portable code (`src/main/kotlin`) and its platform
 * implementations (`src/jvmMain/kotlin`, compiled with `main`) that Detekt cannot express, since
 * its `ForbiddenImport` exemption for `jvmMain` covers the whole rule:
 *
 * - portable code may not refer to a `platform` package, where the JVM implementations live: at
 *   the Multiplatform switch it would not compile;
 * - platform code must itself be in a `platform` package, so the first rule sees every use of it;
 * - platform code may still not use the network APIs, which only `:net` may use.
 *
 * The rules and their limits are in [PlatformSourceRules].
 */
abstract class CheckPlatformSources : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val portableSources: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val platformSources: ConfigurableFileCollection

    /** Qualified-name prefixes platform code may not use, such as `java.net.`. */
    @get:Input
    abstract val forbiddenPlatformNames: ListProperty<String>

    /** Written on success, so the check is up to date until its inputs change. */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    init {
        marker.convention(project.layout.buildDirectory.file("checks/$name.ok"))
    }

    @TaskAction
    fun check() {
        val problems =
            kotlinFiles(portableSources.files).flatMap { PlatformSourceRules.portableProblems(it.path, it.readText()) } +
                kotlinFiles(platformSources.files).flatMap {
                    PlatformSourceRules.platformProblems(it.path, it.readText(), forbiddenPlatformNames.get())
                }
        if (problems.isNotEmpty()) {
            throw GradleException("Platform source rules broken:\n" + problems.joinToString("\n") { "  $it" })
        }
        marker.get().asFile.writeText("ok\n")
    }

    private fun kotlinFiles(roots: Set<File>): List<File> =
        roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }.sorted()
}
