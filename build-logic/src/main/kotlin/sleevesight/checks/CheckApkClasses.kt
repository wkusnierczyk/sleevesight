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
import java.io.File
import java.io.IOException
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
 *
 * It reads code only. `jvmMain` resources, which no module has yet, reach the APK by the same
 * outgoing variants as `jvmMain` classes (`sleevesight.jvm-main`); `JvmMainSourceSetTest` guards
 * those variants, and nothing checks the APK's resource entries.
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
                undefinedTypes(dexesIn(apk), prefix.get()).map { "${apk.name}: ${it.removePrefix("L").removeSuffix(";")}" }
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

        /**
         * The dex files of [apk], read with [readDex]. An APK that cannot be opened, or an entry
         * that cannot be inflated or read as a dex file, fails naming the APK and the entry.
         */
        fun dexesIn(apk: File): List<Dex> {
            val opened =
                try {
                    ZipFile(apk)
                } catch (e: IOException) {
                    throw GradleException("Unreadable APK $apk: $e", e)
                }
            val dexes =
                opened.use { zip ->
                    zip.entries().toList().filter { DEX_ENTRY.matches(it.name) }.map { entry ->
                        try {
                            readDex(zip.getInputStream(entry).use { it.readBytes() })
                        } catch (e: IOException) {
                            throw GradleException("Unreadable dex ${entry.name} in $apk: $e", e)
                        } catch (e: GradleException) {
                            throw GradleException("Unreadable dex ${entry.name} in $apk: ${e.message}", e)
                        }
                    }
                }
            if (dexes.isEmpty()) throw GradleException("No dex file in $apk")
            return dexes
        }

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

        /**
         * Reads the type list and the class definitions of a dex file, of a version up to
         * [MAX_VERSION]. Those hold one dex each; from 041, a file can be a container of several,
         * which this would read only the first of, so it fails on them rather than miss classes.
         * D8 does not write 041 for the app's `minSdk`, 26 (#179). A file it cannot read, such as
         * a truncated one, fails with a [GradleException].
         */
        fun readDex(bytes: ByteArray): Dex {
            if (bytes.size < HEADER_SIZE || String(bytes, 0, 4, Charsets.ISO_8859_1) != "dex\n") {
                throw GradleException("Not a dex file")
            }
            val version = String(bytes, 4, 3, Charsets.ISO_8859_1)
            if (!version.all { it in '0'..'9' } || version.toInt() > MAX_VERSION || bytes[7].toInt() != 0) {
                throw GradleException("Unsupported dex version \"$version\" (read up to 0$MAX_VERSION)")
            }
            // Every dex file D8 writes is little-endian; a byte-swapped one would be read as nonsense.
            val endian = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(ENDIAN_TAG)
            if (endian != ENDIAN_CONSTANT) {
                throw GradleException("Unsupported dex byte order: endian tag 0x${endian.toUInt().toString(16)}, not 0x12345678")
            }
            return try {
                readTables(bytes)
            } catch (e: IndexOutOfBoundsException) {
                throw GradleException("Corrupt dex file: $e", e)
            } catch (e: IllegalArgumentException) {
                throw GradleException("Corrupt dex file: $e", e)
            }
        }

        private fun readTables(bytes: ByteArray): Dex {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val stringIdsOff = buffer.getInt(STRING_IDS_OFF)
            val typeIdsSize = buffer.getInt(TYPE_IDS_SIZE)
            val typeIdsOff = buffer.getInt(TYPE_IDS_OFF)
            val classDefsSize = buffer.getInt(CLASS_DEFS_SIZE)
            val classDefsOff = buffer.getInt(CLASS_DEFS_OFF)
            // Sizes no file this long could hold fail here rather than allocate a list for them.
            require(typeIdsSize in 0..bytes.size / 4) { "type_ids_size $typeIdsSize" }
            require(classDefsSize in 0..bytes.size / CLASS_DEF_SIZE) { "class_defs_size $classDefsSize" }

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

        /** The last dex version whose file holds a single dex. */
        const val MAX_VERSION = 40

        private const val HEADER_SIZE = 0x70
        private const val ENDIAN_TAG = 0x28
        private const val ENDIAN_CONSTANT = 0x12345678
        private const val STRING_IDS_OFF = 0x3C
        private const val TYPE_IDS_SIZE = 0x40
        private const val TYPE_IDS_OFF = 0x44
        private const val CLASS_DEFS_SIZE = 0x60
        private const val CLASS_DEFS_OFF = 0x64
        private const val CLASS_DEF_SIZE = 32
    }
}
