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
 * - platform code may still not import the network APIs, which only `:net` may use.
 */
abstract class CheckPlatformSources : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val portableSources: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val platformSources: ConfigurableFileCollection

    /** Package prefixes platform code may not import, such as `java.net.`. */
    @get:Input
    abstract val forbiddenPlatformImports: ListProperty<String>

    /** Written on success, so the check is up to date until its inputs change. */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    init {
        marker.convention(project.layout.buildDirectory.file("checks/$name.ok"))
    }

    @TaskAction
    fun check() {
        val problems = mutableListOf<String>()
        for (file in kotlinFiles(portableSources.files)) {
            file.readLines().forEachIndexed { index, line ->
                if (PLATFORM_REFERENCE.containsMatchIn(line)) {
                    problems += "${file.path}:${index + 1}: portable code refers to a platform package"
                }
            }
        }
        val forbidden = forbiddenPlatformImports.get()
        for (file in kotlinFiles(platformSources.files)) {
            file.readLines().forEachIndexed { index, line ->
                val imported = IMPORT.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
                if (forbidden.any { imported.startsWith(it) }) {
                    problems += "${file.path}:${index + 1}: platform code imports $imported; network access goes through :net"
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Platform source rules broken:\n" + problems.joinToString("\n") { "  $it" })
        }
        marker.get().asFile.writeText("ok\n")
    }

    private fun kotlinFiles(roots: Set<File>): List<File> =
        roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }.sorted()

    private companion object {
        val PLATFORM_REFERENCE = Regex("""\bio\.github\.wkusnierczyk\.sleevesight\.(?:\w+\.)*platform\b""")
        val IMPORT = Regex("""^\s*import\s+([\w.*`]+)""")
    }
}
