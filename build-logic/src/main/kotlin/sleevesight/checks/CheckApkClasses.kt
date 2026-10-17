package sleevesight.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Fails when an APK's code refers to one of the project's own classes that no dex file of the APK
 * defines: the app would throw `NoClassDefFoundError` when it reached the reference. A build can
 * compile and pass every JVM test while the APK lacks a module's classes, as it lacked `:core`'s
 * `jvmMain` until #177, since the Android Gradle plugin packages a project dependency from other
 * outputs than the ones the compiler and the tests read.
 *
 * It reads the dex files themselves: every type a dex file refers to is in its type list, and every
 * class it defines is in its class definitions. Only types under [prefix] count, since a library
 * may refer to optional classes it looks up before use.
 */
abstract class CheckApkClasses : DefaultTask() {
    /** The variant's APK directory (`SingleArtifact.APK`); every `.apk` in it is read. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val apkDirectory: DirectoryProperty

    /** The type descriptor prefix of the project's classes, such as `Lapp/sleevesight/`. */
    @get:Input
    abstract val prefix: Property<String>

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
                val dexes =
                    ZipFile(apk).use { zip ->
                        zip.entries().toList().filter { DEX_ENTRY.matches(it.name) }.map { zip.getInputStream(it).readBytes() }
                    }
                if (dexes.isEmpty()) throw GradleException("No dex file in $apk")
                undefinedTypes(dexes.map(::readDex), prefix.get()).map { "${apk.name}: ${it.removePrefix("L").removeSuffix(";")}" }
            }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Classes the APK's code refers to but no dex file defines (each would throw NoClassDefFoundError):\n" +
                    problems.joinToString("\n") { "  $it" },
            )
        }
        marker.get().asFile.writeText("ok\n")
    }

    /** The descriptors of the types a dex file refers to and of the classes it defines. */
    data class Dex(
        val types: Set<String>,
        val classes: Set<String>,
    )

    companion object {
        private val DEX_ENTRY = Regex("classes\\d*\\.dex")

        /** The types under [prefix] that some dex file refers to and none defines, sorted; arrays count as their element type. */
        fun undefinedTypes(
            dexes: List<Dex>,
            prefix: String,
        ): List<String> {
            val defined = dexes.flatMapTo(HashSet()) { it.classes }
            return dexes
                .flatMap { it.types }
                .map { it.trimStart('[') }
                .filter { it.startsWith(prefix) && it !in defined }
                .distinct()
                .sorted()
        }

        /** Reads the type list and the class definitions of a dex file (`dex` format, any version). */
        fun readDex(bytes: ByteArray): Dex {
            if (bytes.size < HEADER_SIZE || String(bytes, 0, 4, Charsets.ISO_8859_1) != "dex\n") {
                throw GradleException("Not a dex file")
            }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val stringIdsOff = buffer.getInt(STRING_IDS_OFF)
            val typeIdsSize = buffer.getInt(TYPE_IDS_SIZE)
            val typeIdsOff = buffer.getInt(TYPE_IDS_OFF)
            val classDefsSize = buffer.getInt(CLASS_DEFS_SIZE)
            val classDefsOff = buffer.getInt(CLASS_DEFS_OFF)

            fun string(index: Int): String {
                var at = buffer.getInt(stringIdsOff + 4 * index)
                // The length in UTF-16 units, as a ULEB128, before the null-terminated MUTF-8 bytes.
                while (bytes[at].toInt() and 0x80 != 0) at++
                at++
                var end = at
                while (bytes[end].toInt() != 0) end++
                return String(bytes, at, end - at, Charsets.UTF_8)
            }

            val types = List(typeIdsSize) { string(buffer.getInt(typeIdsOff + 4 * it)) }
            val classes = List(classDefsSize) { types[buffer.getInt(classDefsOff + CLASS_DEF_SIZE * it)] }
            return Dex(types.toSet(), classes.toSet())
        }

        private const val HEADER_SIZE = 0x70
        private const val STRING_IDS_OFF = 0x3C
        private const val TYPE_IDS_SIZE = 0x40
        private const val TYPE_IDS_OFF = 0x44
        private const val CLASS_DEFS_SIZE = 0x60
        private const val CLASS_DEFS_OFF = 0x64
        private const val CLASS_DEF_SIZE = 32
    }
}
