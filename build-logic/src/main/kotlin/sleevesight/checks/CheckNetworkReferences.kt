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
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

/**
 * Fails when a compiled class refers to a network API: network access goes through `:net` only
 * (architecture, Structural constraints). It reads the class files, so it sees every reference the
 * compiler resolved (types in signatures and descriptors, field and method references,
 * instructions, annotations, constants), however the source spelled it: imported, fully
 * qualified, aliased or split over lines. JDK packages need no dependency, so the module graph
 * cannot catch them; Detekt's `ForbiddenImport` catches imports early, in the source.
 *
 * Limit: a class reached by reflection, by name, is not a reference and is not seen.
 */
abstract class CheckNetworkReferences : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    /** Internal-name prefixes no class may refer to, such as `java/net/`. */
    @get:Input
    abstract val forbiddenPrefixes: ListProperty<String>

    /** Written on success, so the check is up to date until its inputs change. */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    init {
        marker.convention(project.layout.buildDirectory.file("checks/$name.ok"))
    }

    @TaskAction
    fun check() {
        val prefixes = forbiddenPrefixes.get()
        val problems =
            classes.asFileTree.files
                .filter { it.extension == "class" }
                .sorted()
                .flatMap { file -> referencesIn(file.readBytes(), prefixes).map { "${file.path}: refers to $it" } }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Network APIs outside :net:\n" + problems.joinToString("\n") { "  $it" },
            )
        }
        marker.get().asFile.writeText("ok\n")
    }

    companion object {
        /** The internal names of the classes [classFile] refers to that start with one of [prefixes]. */
        fun referencesIn(
            classFile: ByteArray,
            prefixes: List<String>,
        ): Set<String> {
            val found = sortedSetOf<String>()
            val recorder =
                object : Remapper(Opcodes.ASM9) {
                    override fun map(internalName: String): String {
                        if (prefixes.any { internalName.startsWith(it) }) found += internalName
                        return internalName
                    }
                }
            // The delegate is a `ClassWriter`, not an empty visitor: `ClassRemapper` remaps only what
            // its delegate visits, and an empty visitor returns no method, field or annotation
            // visitors, so method bodies and annotations would go unread.
            ClassReader(classFile).accept(ClassRemapper(ClassWriter(0), recorder), 0)
            return found
        }
    }
}
