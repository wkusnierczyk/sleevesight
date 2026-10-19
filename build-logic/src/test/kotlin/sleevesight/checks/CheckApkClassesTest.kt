package sleevesight.checks

import org.gradle.api.GradleException
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CheckApkClassesTest {
    @TempDir
    lateinit var tmp: File

    private val prefix = "Lapp/sleevesight/"

    // A dex file whose type list is [types] (each type's descriptor the string of the same index)
    // and which defines the classes [defined]: only the parts `readDex` reads.
    private fun dex(
        types: List<String>,
        defined: List<String>,
        version: String = "035",
    ): ByteArray {
        val header = 0x70
        val stringIds = header
        val typeIds = stringIds + 4 * types.size
        val classDefs = typeIds + 4 * types.size
        val data = classDefs + 32 * defined.size
        val strings = ByteArrayOutputStream()
        val offsets =
            types.map { type ->
                val offset = data + strings.size()
                var length = type.length
                while (length >= 0x80) {
                    strings.write((length and 0x7F) or 0x80)
                    length = length ushr 7
                }
                strings.write(length)
                strings.write(type.toByteArray())
                strings.write(0)
                offset
            }
        val buffer = ByteBuffer.allocate(data + strings.size()).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("dex\n$version\u0000".toByteArray(Charsets.ISO_8859_1))
        buffer.putInt(0x28, 0x12345678)
        buffer.putInt(0x38, types.size).putInt(0x3C, stringIds)
        buffer.putInt(0x40, types.size).putInt(0x44, typeIds)
        buffer.putInt(0x60, defined.size).putInt(0x64, classDefs)
        offsets.forEachIndexed { i, offset -> buffer.putInt(stringIds + 4 * i, offset) }
        types.indices.forEach { buffer.putInt(typeIds + 4 * it, it) }
        defined.forEachIndexed { i, type -> buffer.putInt(classDefs + 32 * i, types.indexOf(type)) }
        buffer.position(data)
        buffer.put(strings.toByteArray())
        return buffer.array()
    }

    @Test
    fun `a dex file's types and classes are read`() {
        val long = "Lapp/sleevesight/" + "a".repeat(200) + ";"
        val read = CheckApkClasses.readDex(dex(listOf("Lapp/sleevesight/A;", long, "Ljava/lang/Object;"), listOf(long)))
        assertEquals(setOf("Lapp/sleevesight/A;", long, "Ljava/lang/Object;"), read.types)
        assertEquals(setOf(long), read.classes)
    }

    @Test
    fun `a project class referred to and defined nowhere is reported`() {
        val app =
            dex(
                listOf("Lapp/sleevesight/app/AppGraph;", "Lapp/sleevesight/core/platform/JvmHasher;", "Ljava/lang/Object;"),
                listOf("Lapp/sleevesight/app/AppGraph;"),
            )
        assertEquals(
            listOf("Lapp/sleevesight/core/platform/JvmHasher;"),
            CheckApkClasses.undefinedTypes(listOf(CheckApkClasses.readDex(app)), prefix),
        )
    }

    @Test
    fun `a class defined in another dex file of the APK is not reported`() {
        val first = dex(listOf("Lapp/sleevesight/A;", "Lapp/sleevesight/B;"), listOf("Lapp/sleevesight/A;"))
        val second = dex(listOf("Lapp/sleevesight/B;"), listOf("Lapp/sleevesight/B;"))
        assertEquals(emptyList(), CheckApkClasses.undefinedTypes(listOf(first, second).map(CheckApkClasses::readDex), prefix))
    }

    @Test
    fun `an array counts as its element type, and other packages do not count`() {
        val bytes = dex(listOf("[[Lapp/sleevesight/Missing;", "Lorg/conscrypt/Conscrypt;"), emptyList())
        assertEquals(
            listOf("Lapp/sleevesight/Missing;"),
            CheckApkClasses.undefinedTypes(listOf(CheckApkClasses.readDex(bytes)), prefix),
        )
    }

    @Test
    fun `a file that is not a dex file fails`() {
        assertFailsWith<GradleException> { CheckApkClasses.readDex(ByteArray(0x70)) }
    }

    @Test
    fun `versions up to 040 are read, and 041, which may hold several dex files, fails`() {
        for (version in listOf("035", "037", "038", "039", "040")) {
            val read = CheckApkClasses.readDex(dex(listOf("Lapp/sleevesight/A;"), emptyList(), version))
            assertEquals(setOf("Lapp/sleevesight/A;"), read.types)
        }
        val container = dex(listOf("Lapp/sleevesight/A;"), emptyList(), "041")
        val failed = assertFailsWith<GradleException> { CheckApkClasses.readDex(container) }
        assertEquals("Unsupported dex version \"041\" (read up to 040)", failed.message)
    }

    @Test
    fun `a version that is not three digits fails`() {
        for (version in listOf("-01", "+35", " 35")) {
            val failed =
                assertFailsWith<GradleException> { CheckApkClasses.readDex(dex(listOf("Lapp/sleevesight/A;"), emptyList(), version)) }
            assertEquals("Unsupported dex version \"$version\" (read up to 040)", failed.message)
        }
    }

    @Test
    fun `a byte-swapped dex file fails, saying why`() {
        val bytes = dex(listOf("Lapp/sleevesight/A;"), emptyList())
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0x28, 0x78563412)
        val failed = assertFailsWith<GradleException> { CheckApkClasses.readDex(bytes) }
        assertEquals("Unsupported dex byte order: endian tag 0x78563412, not 0x12345678", failed.message)
    }

    // An APK holding [entries], each stored deflated.
    private fun apk(vararg entries: Pair<String, ByteArray>): File {
        val file = File.createTempFile("probe", ".apk", tmp)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `an APK's dex files are read, and other entries are not`() {
        val file =
            apk(
                "classes.dex" to dex(listOf("Lapp/sleevesight/A;"), listOf("Lapp/sleevesight/A;")),
                "classes2.dex" to dex(listOf("Lapp/sleevesight/B;"), listOf("Lapp/sleevesight/B;")),
                "assets/notes.dex.txt" to byteArrayOf(1, 2, 3),
            )
        assertEquals(
            setOf("Lapp/sleevesight/A;", "Lapp/sleevesight/B;"),
            CheckApkClasses.dexesIn(file).flatMap { it.classes }.toSet(),
        )
    }

    @Test
    fun `an entry that does not inflate fails, naming the entry and the APK`() {
        val file = apk("classes.dex" to dex(listOf("Lapp/sleevesight/A;"), emptyList()))
        val bytes = file.readBytes()
        // The deflated data follows the 30-byte local header, the name and the extra field (their
        // lengths at 26 and 28); a first byte of 0x07 opens a final block of the reserved type 3,
        // which no inflater accepts.
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes[30 + header.getShort(26) + header.getShort(28)] = 0x07
        file.writeBytes(bytes)
        val failed = assertFailsWith<GradleException> { CheckApkClasses.dexesIn(file) }
        assertTrue(failed.message!!.startsWith("Unreadable dex classes.dex in $file: java.util.zip.ZipException"), failed.message)
        assertTrue(failed.cause is ZipException, "${failed.cause}")
    }

    @Test
    fun `a dex entry that is not a dex file fails, naming the entry and the APK`() {
        val file = apk("classes3.dex" to ByteArray(0x70))
        val failed = assertFailsWith<GradleException> { CheckApkClasses.dexesIn(file) }
        assertEquals("Unreadable dex classes3.dex in $file: Not a dex file", failed.message)
    }

    @Test
    fun `a truncated dex file fails as corrupt`() {
        val whole = dex(listOf("Lapp/sleevesight/A;", "Lapp/sleevesight/B;"), listOf("Lapp/sleevesight/A;"))
        // Cut inside the type list, and inside the last string.
        for (length in listOf(0x70 + 6, whole.size - 2)) {
            val failed = assertFailsWith<GradleException> { CheckApkClasses.readDex(whole.copyOf(length)) }
            assertTrue(failed.message!!.startsWith("Corrupt dex file: "), failed.message)
        }
    }

    @Test
    fun `a size larger than the file fails as corrupt`() {
        val bytes = dex(listOf("Lapp/sleevesight/A;"), emptyList())
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0x40, Int.MAX_VALUE)
        val failed = assertFailsWith<GradleException> { CheckApkClasses.readDex(bytes) }
        assertTrue(failed.message!!.startsWith("Corrupt dex file: "), failed.message)
    }
}
