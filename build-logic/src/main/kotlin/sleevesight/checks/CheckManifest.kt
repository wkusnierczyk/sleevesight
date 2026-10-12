package sleevesight.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Fails when the merged release manifest requests a permission that is not in the permission
 * allowlist. Libraries can merge permissions in unseen; this makes each one a reviewed edit.
 * `${applicationId}` in the allowlist stands for the application id.
 */
abstract class CheckManifest : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val allowlist: RegularFileProperty

    @get:Input
    abstract val applicationId: Property<String>

    @TaskAction
    fun check() {
        val doc =
            DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(mergedManifest.get().asFile)
        val requested =
            doc.getElementsByTagName("uses-permission").let { nodes ->
                (0 until nodes.length).map { i ->
                    nodes.item(i).attributes.getNamedItemNS(ANDROID_NS, "name").nodeValue
                }
            }
        val allowed =
            allowlist.get().asFile.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .map { it.replace("\${applicationId}", applicationId.get()) }
                .toSet()
        val extra = requested.filterNot { it in allowed }
        if (extra.isNotEmpty()) {
            throw GradleException(
                "Permissions outside ${allowlist.get().asFile.name}:\n" +
                    extra.joinToString("\n") { "  $it" } +
                    "\nRemove them with tools:node=\"remove\", or add them after review.",
            )
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
