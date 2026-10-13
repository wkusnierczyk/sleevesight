package sleevesight.checks

import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `sleevesight.jvm-main` on a small two-module build: portable code cannot see `jvmMain`, while
 * `jvmMain` sees `main`'s internals, and the module's tests, jar and consumers see both.
 */
class JvmMainSourceSetTest {
    private fun project(): File {
        val dir = Files.createTempDirectory("jvm-main").toFile()
        dir.write(
            "settings.gradle.kts",
            "dependencyResolutionManagement { repositories { mavenCentral() } }\n" +
                "rootProject.name = \"probe\"\n" +
                "include(\":lib\", \":consumer\")\n",
        )
        dir.write(
            "gradle.properties",
            "kotlin.build.archivesTaskOutputAsFriendModule=false\nkotlin.compiler.execution.strategy=in-process\n",
        )
        dir.write("lib/build.gradle.kts", "plugins { id(\"sleevesight.jvm-main\") }\n")
        dir.write(
            "lib/src/main/kotlin/p/Hasher.kt",
            "package p\n\nfun interface Hasher { fun hash(s: String): String }\n\ninternal fun secret(): String = \"s\"\n",
        )
        dir.write(
            "lib/src/jvmMain/kotlin/p/platform/JvmHasher.kt",
            "package p.platform\n\nimport p.Hasher\nimport p.secret\n\n" +
                "object JvmHasher : Hasher { override fun hash(s: String): String = s + secret() }\n",
        )
        dir.write("lib/src/test/kotlin/p/HasherTest.kt", "package p\n\nval tested: Hasher = p.platform.JvmHasher\n")
        dir.write(
            "consumer/build.gradle.kts",
            "plugins { kotlin(\"jvm\") }\n\ndependencies { implementation(project(\":lib\")) }\n",
        )
        dir.write("consumer/src/main/kotlin/c/Use.kt", "package c\n\nval used: p.Hasher = p.platform.JvmHasher\n")
        return dir
    }

    private fun File.write(
        path: String,
        text: String,
    ) = resolve(path).apply { parentFile.mkdirs() }.writeText(text)

    private fun runner(
        dir: File,
        vararg tasks: String,
    ) = GradleRunner.create().withProjectDir(dir).withPluginClasspath().withArguments(*tasks, "--stacktrace")

    @Test
    fun `jvmMain is separate from main and carried by the module`() {
        val dir = project()
        runner(dir, ":lib:compileTestKotlin", ":lib:jar", ":consumer:compileKotlin", ":lib:checkSourceRoots").build()
        val jar = dir.resolve("lib/build/libs").listFiles()!!.single { it.name.endsWith(".jar") }
        ZipFile(jar).use { zip ->
            assertNotNull(zip.getEntry("p/platform/JvmHasher.class"), "JvmHasher in the jar")
            assertNotNull(zip.getEntry("p/Hasher.class"), "Hasher in the jar")
        }

        // Portable code that refers to a JVM implementation does not compile.
        dir.write("lib/src/main/kotlin/p/Sneaky.kt", "package p\n\nval sneaky: Hasher = p.platform.JvmHasher\n")
        val failed = runner(dir, ":lib:compileKotlin").buildAndFail()
        assertTrue("Unresolved reference" in failed.output, failed.output)
        dir.resolve("lib/src/main/kotlin/p/Sneaky.kt").delete()

        // Nothing compiles from src/main/java.
        dir.write("lib/src/main/java/p/Hidden.kt", "package p\n\nobject Hidden\n")
        val hidden = runner(dir, ":lib:checkSourceRoots").buildAndFail()
        assertTrue("Hidden.kt" in hidden.output, hidden.output)
        dir.deleteRecursively()
    }
}
