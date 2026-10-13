package sleevesight.checks

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
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
    fun `a type in a generic signature is found`() {
        val bytes =
            probe(members = {
                it.visitField(Opcodes.ACC_PRIVATE, "us", "Ljava/util/List;", "Ljava/util/List<Ljava/net/URI;>;", null)
            })
        assertEquals(setOf("java/net/URI"), CheckNetworkReferences.referencesIn(bytes, network))
    }

    @Test
    fun `a class in an annotation value is found`() {
        val bytes =
            probe(members = { writer ->
                writer.visitAnnotation("LMarker;", true).apply {
                    visit("v", Type.getType("Ljava/net/URI;"))
                    visitEnd()
                }
            })
        assertEquals(setOf("java/net/URI"), CheckNetworkReferences.referencesIn(bytes, network))
    }

    @Test
    fun `a class literal is found`() {
        val bytes =
            probe(body = { method ->
                method.visitLdcInsn(Type.getType("Ljava/net/URI;"))
                method.visitInsn(Opcodes.POP)
            })
        assertEquals(setOf("java/net/URI"), CheckNetworkReferences.referencesIn(bytes, network))
    }

    @Test
    fun `a method handle in an invokedynamic is found`() {
        val metafactory =
            Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                    "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                    "Ljava/lang/invoke/CallSite;",
                false,
            )
        val create = Handle(Opcodes.H_INVOKESTATIC, "java/net/URI", "create", "(Ljava/lang/String;)Ljava/net/URI;", false)
        val bytes =
            probe(body = { method ->
                method.visitInvokeDynamicInsn(
                    "apply",
                    "()Ljava/util/function/Function;",
                    metafactory,
                    Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                    create,
                    Type.getType("(Ljava/lang/String;)Ljava/lang/Object;"),
                )
                method.visitInsn(Opcodes.POP)
            })
        assertEquals(setOf("java/net/URI"), CheckNetworkReferences.referencesIn(bytes, network))
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
