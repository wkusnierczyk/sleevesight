package sleevesight

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `checkPortableCode` (`sleevesight.pipeline`) on a one-module build: a pipeline module whose
 * `main` uses `synchronized`, the access-order `LinkedHashMap` constructor, `String.format` or a
 * `LinkedHashMap` subclass overriding `removeEldestEntry` fails it, each written the usual Kotlin
 * way, which Detekt misses, and so does one whose tests or test fixtures use one; the same code in
 * `jvmMain` passes.
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
            "fun formatted(n: Int): String = \"%d\".format(n)\n\n" +
            bounded

    private fun project(
        main: String,
        test: String? = null,
        fixture: String? = null,
    ) {
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
        val fixtures = if (fixture != null) "\n    `java-test-fixtures`\n" else " "
        dir.write("lib/build.gradle.kts", "plugins {${fixtures}id(\"sleevesight.pipeline\") }\n")
        dir.write("lib/src/main/kotlin/p/Portable.kt", "package p\n\n$main")
        dir.write("lib/src/jvmMain/kotlin/p/platform/Jvm.kt", "package p.platform\n\n$jvmOnly")
        test?.let { dir.write("lib/src/test/kotlin/p/PortableTest.kt", "package p\n\n$it") }
        fixture?.let { dir.write("lib/src/testFixtures/kotlin/p/Fixture.kt", "package p\n\n$it") }
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
        classFile: String = "PortableKt.class",
        test: String? = null,
        fixture: String? = null,
    ) {
        project(main, test, fixture)
        val failed = runner(":lib:checkPortableCode").buildAndFail()
        assertTrue("JVM-only constructs in portable code" in failed.output, failed.output)
        assertTrue(classFile in failed.output, failed.output)
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

    @Test
    fun `a LinkedHashMap subclass overriding removeEldestEntry in main fails`() {
        assertFailsNaming(bounded, "java/util/LinkedHashMap.removeEldestEntry override", classFile = "Bounded.class")
    }

    @Test
    fun `a removeEldestEntry override two levels below LinkedHashMap in main fails`() {
        assertFailsNaming(
            "open class Base : LinkedHashMap<String, Int>()\n\n" +
                "class Deeper : Base() {\n" +
                "    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =\n" +
                "        size > 2\n" +
                "}\n",
            "java/util/LinkedHashMap.removeEldestEntry override",
            classFile = "Deeper.class",
        )
    }

    @Test
    fun `a removeEldestEntry override in a test, below a LinkedHashMap subclass in main, fails`() {
        assertFailsNaming(
            "open class Base : LinkedHashMap<String, Int>()\n",
            "java/util/LinkedHashMap.removeEldestEntry override",
            classFile = "DeeperInTest.class",
            test =
                "class DeeperInTest : Base() {\n" +
                    "    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =\n" +
                    "        size > 2\n" +
                    "}\n",
        )
    }

    @Test
    fun `synchronized in a test fails`() {
        assertFailsNaming(
            "fun portable(): Int = 1\n",
            "monitorenter (synchronized)",
            classFile = "PortableTestKt.class",
            test = "fun lockedInTest(lock: Any): Int = synchronized(lock) { 1 }\n",
        )
    }

    @Test
    fun `String format in a test fixture fails`() {
        assertFailsNaming(
            "fun portable(): Int = 1\n",
            "java/lang/String.format",
            classFile = "FixtureKt.class",
            fixture = "fun formattedInFixture(n: Int): String = \"%d\".format(n)\n",
        )
    }

    private companion object {
        // An LRU bounded the JVM way, which `kotlin.collections.LinkedHashMap` has no member for.
        const val bounded =
            "class Bounded : LinkedHashMap<String, Int>() {\n" +
                "    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean =\n" +
                "        size > 2\n" +
                "}\n"
    }
}
