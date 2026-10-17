package sleevesight.checks

import org.gradle.api.GradleException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CheckApkClassesTest {
    private val prefix = "Lapp/sleevesight/"

    // A dex file whose type list is [types] (each type's descriptor the string of the same index)
    // and which defines the classes [defined]: only the parts `readDex` reads.
    private fun dex(
        types: List<String>,
        defined: List<String>,
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
        buffer.put("dex\n035\u0000".toByteArray(Charsets.ISO_8859_1))
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
}
