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
 * Fails when a compiled class of the portable code, a pipeline module's `main`, its tests and its
 * test fixtures (which become `commonMain` and mostly `commonTest` at the Multiplatform switch;
 * tests of `jvmMain` code, which would become `jvmTest`, are held to the same rules), uses a
 * JVM-only construct that needs no import, so Detekt's `ForbiddenImport` cannot see it and its
 * `ForbiddenMethodCall` misses it as Kotlin usually writes it (`config/detekt/pipeline.yml`):
 *
 * - `synchronized(…)`, a `monitorenter` or `monitorexit` instruction, and `@Synchronized`, a method
 *   flagged `ACC_SYNCHRONIZED`;
 * - the access-order constructor `LinkedHashMap(capacity, loadFactor, true)`,
 *   `java/util/LinkedHashMap.<init>(IFZ)V`, also when a subclass calls it;
 * - `String.format` and `"…".format(…)`, a call to `java/lang/String.format`, either overload;
 * - an override of `removeEldestEntry` in a class descended from `java/util/LinkedHashMap`, the
 *   usual way to bound an LRU, a JVM-only member (`kotlin.collections.LinkedHashMap` has none to
 *   override). The ancestry is followed through the classes the check reads (`main`, the tests and
 *   the test fixtures together); a superclass outside them, in a dependency or another module, is
 *   not resolved, so an override under it is missed unless that superclass is `LinkedHashMap`.
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
        val read =
            classes.asFileTree.files
                .filter { it.extension == "class" }
                .sorted()
                .map { it to it.readBytes() }
        val supers = read.mapNotNull { (_, bytes) -> superclassOf(bytes) }.toMap()
        val problems =
            read.flatMap { (file, bytes) -> jvmOnlyUsesIn(bytes, supers).map { "${file.path}: $it" } }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "JVM-only constructs in portable code (use jvmMain, or a common API):\n" +
                    problems.joinToString("\n") { "  $it" },
            )
        }
        marker.get().asFile.writeText("ok\n")
    }

    companion object {
        /** [classFile]'s internal name and its superclass's, if it has one. */
        fun superclassOf(classFile: ByteArray): Pair<String, String>? {
            val reader = ClassReader(classFile)
            return reader.superName?.let { reader.className to it }
        }

        /**
         * The JVM-only constructs [classFile] uses, each as `<method>: <construct>`, once per method.
         * [supers] maps the internal names of the other classes read to their superclasses', so an
         * override of `removeEldestEntry` is found however far below `LinkedHashMap` the class is.
         */
        fun jvmOnlyUsesIn(
            classFile: ByteArray,
            supers: Map<String, String> = emptyMap(),
        ): List<String> {
            val found = mutableListOf<String>()
            val visitor =
                object : ClassVisitor(Opcodes.ASM9) {
                    private var superName: String? = null

                    override fun visit(
                        version: Int,
                        access: Int,
                        name: String,
                        signature: String?,
                        superName: String?,
                        interfaces: Array<out String>?,
                    ) {
                        this.superName = superName
                    }

                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor {
                        val method = "$name$descriptor"
                        if (access and Opcodes.ACC_SYNCHRONIZED != 0) found += "$method: @Synchronized"
                        if (method == REMOVE_ELDEST_ENTRY && descendsFromLinkedHashMap(superName, supers)) {
                            found += "$method: java/util/LinkedHashMap.removeEldestEntry override"
                        }
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

        // Whether [superName], or a superclass of it found in [supers], is `java/util/LinkedHashMap`.
        private fun descendsFromLinkedHashMap(
            superName: String?,
            supers: Map<String, String>,
        ): Boolean {
            val seen = mutableSetOf<String>()
            var next = superName
            while (next != null && seen.add(next)) {
                if (next == LINKED_HASH_MAP) return true
                next = supers[next]
            }
            return false
        }

        private const val LINKED_HASH_MAP = "java/util/LinkedHashMap"
        private const val REMOVE_ELDEST_ENTRY = "removeEldestEntry(Ljava/util/Map\$Entry;)Z"
    }
}
