package sleevesight.checks

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckNetworkReferencesTest {
    private val network = listOf("java/net/", "javax/net/", "okhttp3/", "io/ktor/client/")

    // A class `Probe` with the members [members] adds, and a static method `run` that runs [body].
    private fun probe(
        members: (ClassWriter) -> Unit = {},
        body: (MethodVisitor) -> Unit = {},
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "Probe", null, "java/lang/Object", null)
        members(writer)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "run", "()V", null, null).apply {
            visitCode()
            body(this)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `a constructor call to java net URL is found`() {
        val bytes =
            probe(body = { method ->
                method.visitTypeInsn(Opcodes.NEW, "java/net/URL")
                method.visitInsn(Opcodes.DUP)
                method.visitLdcInsn("https://example.org")
                method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/net/URL", "<init>", "(Ljava/lang/String;)V", false)
                method.visitInsn(Opcodes.POP)
            })
        assertEquals(setOf("java/net/URL"), CheckNetworkReferences.referencesIn(bytes, network))
    }

    @Test
    fun `a type in a field or method descriptor is found`() {
        val field = probe(members = { it.visitField(Opcodes.ACC_PRIVATE, "u", "Ljavax/net/SocketFactory;", null, null) })
        assertEquals(setOf("javax/net/SocketFactory"), CheckNetworkReferences.referencesIn(field, network))
        val method =
            probe(members = {
                it.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "c", "()Lokhttp3/OkHttpClient;", null, null)
            })
        assertEquals(setOf("okhttp3/OkHttpClient"), CheckNetworkReferences.referencesIn(method, network))
    }

    @Test
    fun `a class without network references passes`() {
        val bytes =
            probe(body = { method ->
                method.visitLdcInsn("SHA-256")
                method.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/security/MessageDigest",
                    "getInstance",
                    "(Ljava/lang/String;)Ljava/security/MessageDigest;",
                    false,
                )
                method.visitInsn(Opcodes.POP)
                // A string naming the class is not a reference.
                method.visitLdcInsn("java/net/URL")
                method.visitInsn(Opcodes.POP)
            })
        assertEquals(emptySet(), CheckNetworkReferences.referencesIn(bytes, network))
    }
}
