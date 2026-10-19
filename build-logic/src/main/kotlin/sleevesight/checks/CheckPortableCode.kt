package sleevesight.checks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Fails when a compiled class of the portable code, a pipeline module's `main`, uses a JVM-only
 * construct that needs no import, so Detekt's `ForbiddenImport` cannot see it and its
 * `ForbiddenMethodCall` misses it as Kotlin usually writes it (`config/detekt/pipeline.yml`):
 *
 * - `synchronized(…)`, a `monitorenter` or `monitorexit` instruction, and `@Synchronized`, a method
 *   flagged `ACC_SYNCHRONIZED`;
 * - the access-order constructor `LinkedHashMap(capacity, loadFactor, true)`,
 *   `java/util/LinkedHashMap.<init>(IFZ)V`, also when a subclass calls it;
 * - `String.format` and `"…".format(…)`, a call to `java/lang/String.format`, either overload.
 *
 * It reads the class files, so it sees each one however the source spelled it (an alias, a full
 * name, an inline function's body). Detekt stays the early signal, in the source. `jvmMain`, where
 * JVM APIs are allowed, is not read. Like Detekt, it is not a complete check: only compiling a
 * non-JVM target is.
 */
abstract class CheckPortableCode : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    /** Written on success, so the check is up to date until its inputs change. */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    init {
        marker.convention(project.layout.buildDirectory.file("checks/$name.ok"))
    }

    @TaskAction
    fun check() {
        val problems =
            classes.asFileTree.files
                .filter { it.extension == "class" }
                .sorted()
                .flatMap { file -> jvmOnlyUsesIn(file.readBytes()).map { "${file.path}: $it" } }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "JVM-only constructs in portable code (use jvmMain, or a common API):\n" +
                    problems.joinToString("\n") { "  $it" },
            )
        }
        marker.get().asFile.writeText("ok\n")
    }

    companion object {
        /** The JVM-only constructs [classFile] uses, each as `<method>: <construct>`, once per method. */
        fun jvmOnlyUsesIn(classFile: ByteArray): List<String> {
            val found = mutableListOf<String>()
            val visitor =
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor {
                        val method = "$name$descriptor"
                        if (access and Opcodes.ACC_SYNCHRONIZED != 0) found += "$method: @Synchronized"
                        return object : MethodVisitor(Opcodes.ASM9) {
                            override fun visitInsn(opcode: Int) {
                                when (opcode) {
                                    Opcodes.MONITORENTER -> found += "$method: monitorenter (synchronized)"
                                    Opcodes.MONITOREXIT -> found += "$method: monitorexit (synchronized)"
                                }
                            }

                            override fun visitMethodInsn(
                                opcode: Int,
                                owner: String,
                                name: String,
                                descriptor: String,
                                isInterface: Boolean,
                            ) {
                                if (owner == "java/util/LinkedHashMap" && name == "<init>" && descriptor == "(IFZ)V") {
                                    found += "$method: java/util/LinkedHashMap.<init>(IFZ)V (access order)"
                                }
                                if (owner == "java/lang/String" && name == "format") {
                                    found += "$method: java/lang/String.format"
                                }
                            }
                        }
                    }
                }
            ClassReader(classFile).accept(visitor, 0)
            // A `synchronized` block exits its monitor on each path out, so once per method is enough.
            return found.distinct()
        }
    }
}
