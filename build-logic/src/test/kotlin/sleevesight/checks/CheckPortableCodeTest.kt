package sleevesight.checks

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckPortableCodeTest {
    // A class `Probe` with a static method `run`, flagged [access] as well, that runs [body].
    private fun probe(
        access: Int = 0,
        body: (MethodVisitor) -> Unit = {},
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Probe", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or access, "run", "()V", null, null).apply {
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
    fun `a monitor is found once per method`() {
        val bytes =
            probe(body = { method ->
                method.visitLdcInsn("lock")
                method.visitInsn(Opcodes.DUP)
                method.visitInsn(Opcodes.MONITORENTER)
                method.visitInsn(Opcodes.MONITOREXIT)
                method.visitLdcInsn("lock")
                method.visitInsn(Opcodes.MONITOREXIT)
            })
        assertEquals(
            listOf("run()V: monitorenter (synchronized)", "run()V: monitorexit (synchronized)"),
            CheckPortableCode.jvmOnlyUsesIn(bytes),
        )
    }

    @Test
    fun `a synchronized method is found`() {
        assertEquals(listOf("run()V: @Synchronized"), CheckPortableCode.jvmOnlyUsesIn(probe(access = Opcodes.ACC_SYNCHRONIZED)))
    }

    @Test
    fun `the access-order LinkedHashMap constructor is found, and the others are not`() {
        fun construct(descriptor: String, push: (MethodVisitor) -> Unit) =
            probe(body = { method ->
                method.visitTypeInsn(Opcodes.NEW, "java/util/LinkedHashMap")
                method.visitInsn(Opcodes.DUP)
                push(method)
                method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/LinkedHashMap", "<init>", descriptor, false)
                method.visitInsn(Opcodes.POP)
            })
        val accessOrder =
            construct("(IFZ)V") {
                it.visitLdcInsn(16)
                it.visitLdcInsn(0.75f)
                it.visitInsn(Opcodes.ICONST_1)
            }
        assertEquals(
            listOf("run()V: java/util/LinkedHashMap.<init>(IFZ)V (access order)"),
            CheckPortableCode.jvmOnlyUsesIn(accessOrder),
        )
        val plain = construct("(IF)V") {
            it.visitLdcInsn(16)
            it.visitLdcInsn(0.75f)
        }
        assertEquals(emptyList(), CheckPortableCode.jvmOnlyUsesIn(plain))
    }

    @Test
    fun `String format is found, either overload`() {
        val plain =
            probe(body = { method ->
                method.visitLdcInsn("%d")
                method.visitInsn(Opcodes.ICONST_0)
                method.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
                method.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/String",
                    "format",
                    "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;",
                    false,
                )
                method.visitInsn(Opcodes.POP)
            })
        assertEquals(listOf("run()V: java/lang/String.format"), CheckPortableCode.jvmOnlyUsesIn(plain))
        val localized =
            probe(body = { method ->
                method.visitInsn(Opcodes.ACONST_NULL)
                method.visitLdcInsn("%d")
                method.visitInsn(Opcodes.ICONST_0)
                method.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object")
                method.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "java/lang/String",
                    "format",
                    "(Ljava/util/Locale;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;",
                    false,
                )
                method.visitInsn(Opcodes.POP)
            })
        assertEquals(listOf("run()V: java/lang/String.format"), CheckPortableCode.jvmOnlyUsesIn(localized))
    }

    @Test
    fun `a removeEldestEntry override in a LinkedHashMap subclass is found, and elsewhere it is not`() {
        fun lru(superName: String): ByteArray {
            val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "Lru", null, superName, null)
            val access = Opcodes.ACC_PROTECTED
            writer.visitMethod(access, "removeEldestEntry", "(Ljava/util/Map\$Entry;)Z", null, null).apply {
                visitCode()
                visitInsn(Opcodes.ICONST_0)
                visitInsn(Opcodes.IRETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            writer.visitEnd()
            return writer.toByteArray()
        }
        assertEquals(
            listOf("removeEldestEntry(Ljava/util/Map\$Entry;)Z: java/util/LinkedHashMap.removeEldestEntry override"),
            CheckPortableCode.jvmOnlyUsesIn(lru("java/util/LinkedHashMap")),
        )
        assertEquals(emptyList(), CheckPortableCode.jvmOnlyUsesIn(lru("java/lang/Object")))
    }

    @Test
    fun `a class without them passes`() {
        val bytes =
            probe(body = { method ->
                method.visitLdcInsn("a")
                method.visitLdcInsn("b")
                method.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/String",
                    "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    false,
                )
                method.visitInsn(Opcodes.POP)
            })
        assertEquals(emptyList(), CheckPortableCode.jvmOnlyUsesIn(bytes))
    }
}
