package sleevesight

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `checkPortableCode` (`sleevesight.pipeline`) on a one-module build: a pipeline module whose
 * `main` uses `synchronized`, the access-order `LinkedHashMap` constructor or `String.format` fails
 * it, each written the usual Kotlin way, which Detekt misses; the same code in `jvmMain` passes.
 *
 * A functional test (`./gradlew :build-logic:functionalTest`, run by CI after the build): the
 * nested build resolves Kotlin from Maven Central, so it stays out of `check`.
 */
class PortableCodeTest {
    @TempDir
    lateinit var dir: File

    private val jvmOnly =
        "fun locked(lock: Any): Int = synchronized(lock) { 1 }\n\n" +
            "fun lru(): MutableMap<String, Int> = LinkedHashMap(16, 0.75f, true)\n\n" +
            "fun formatted(n: Int): String = \"%d\".format(n)\n"

    private fun project(main: String) {
        dir.write(
            "settings.gradle.kts",
            "dependencyResolutionManagement { repositories { mavenCentral() } }\n" +
                "rootProject.name = \"probe\"\n" +
                "include(\":lib\")\n",
        )
        dir.write(
            "gradle.properties",
            "kotlin.build.archivesTaskOutputAsFriendModule=false\nkotlin.compiler.execution.strategy=in-process\n",
        )
        dir.write("lib/build.gradle.kts", "plugins { id(\"sleevesight.pipeline\") }\n")
        dir.write("lib/src/main/kotlin/p/Portable.kt", "package p\n\n$main")
        dir.write("lib/src/jvmMain/kotlin/p/platform/Jvm.kt", "package p.platform\n\n$jvmOnly")
    }

    private fun File.write(
        path: String,
        text: String,
    ) = resolve(path).apply { parentFile.mkdirs() }.writeText(text)

    private fun runner(vararg arguments: String) =
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withTestKitDir(File(System.getProperty("testkit.dir")))
            .withPluginClasspath()
            .withArguments(*arguments, "--stacktrace")

    private fun assertFailsNaming(
        main: String,
        construct: String,
    ) {
        project(main)
        val failed = runner(":lib:checkPortableCode").buildAndFail()
        assertTrue("JVM-only constructs in portable code" in failed.output, failed.output)
        assertTrue("PortableKt.class" in failed.output, failed.output)
        assertTrue(construct in failed.output, failed.output)
    }

    @Test
    fun `JVM-only constructs in jvmMain pass`() {
        project("fun portable(): Int = 1\n")
        // `jvmMain` compiled too, so the check would see its classes if it read them.
        runner(":lib:compileJvmMainKotlin", ":lib:checkPortableCode").build()
        assertTrue(dir.resolve("lib/build/classes/kotlin/jvmMain/p/platform/JvmKt.class").isFile)
    }

    @Test
    fun `synchronized in main fails`() {
        assertFailsNaming("fun locked(lock: Any): Int = synchronized(lock) { 1 }\n", "monitorenter (synchronized)")
    }

    @Test
    fun `the access-order LinkedHashMap constructor in main fails`() {
        assertFailsNaming(
            "fun lru(): MutableMap<String, Int> = LinkedHashMap(16, 0.75f, true)\n",
            "java/util/LinkedHashMap.<init>(IFZ)V (access order)",
        )
    }

    @Test
    fun `String format in main fails`() {
        assertFailsNaming("fun formatted(n: Int): String = \"%d\".format(n)\n", "java/lang/String.format")
    }
}
