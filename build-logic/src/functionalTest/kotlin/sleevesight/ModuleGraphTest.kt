package sleevesight

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `checkModuleGraph` (`sleevesight.kotlin-jvm`) on a small three-module build, `:a` → `:b` → `:c`,
 * with `compile-forbidden.a=c`: `:c` behind an `implementation` dependency of `:b` passes; behind an
 * `api` one, which puts it on `:a`'s compile classpath, it fails; so does a test dependency of `:a`'s.
 *
 * A functional test (`./gradlew :build-logic:functionalTest`, run by CI after the build): the
 * nested build resolves Kotlin from Maven Central, so it stays out of `check`.
 */
class ModuleGraphTest {
    @TempDir
    lateinit var dir: File

    private fun project(
        bToC: String,
        aExtra: String = "",
    ) {
        dir.write(
            "settings.gradle.kts",
            "dependencyResolutionManagement { repositories { mavenCentral() } }\n" +
                "rootProject.name = \"probe\"\n" +
                "include(\":a\", \":b\", \":c\")\n",
        )
        dir.write(
            "config/module-graph.properties",
            "a=b,c\nb=c\nc=\ncompile-forbidden.a=c\nnetwork-groups=io.ktor\nnetwork-modules=c\n",
        )
        dir.write(
            "a/build.gradle.kts",
            "plugins { id(\"sleevesight.kotlin-jvm\") }\n\n" +
                "dependencies {\n    implementation(project(\":b\"))\n$aExtra}\n",
        )
        dir.write(
            "b/build.gradle.kts",
            "plugins {\n    id(\"sleevesight.kotlin-jvm\")\n    `java-library`\n}\n\n" +
                "dependencies { $bToC(project(\":c\")) }\n",
        )
        dir.write("c/build.gradle.kts", "plugins { id(\"sleevesight.kotlin-jvm\") }\n")
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

    private val forbidden = ":a may not have :c on its compile classpath, even through another module"

    @Test
    fun `a forbidden module behind an implementation dependency passes, and the check is reused`() {
        project(bToC = "implementation")
        runner(":a:checkModuleGraph", "--configuration-cache").build()
        val again = runner(":a:checkModuleGraph", "--configuration-cache").build()
        assertTrue("Reusing configuration cache." in again.output, again.output)
        assertEquals(TaskOutcome.UP_TO_DATE, again.task(":a:checkModuleGraph")?.outcome)
    }

    @Test
    fun `a forbidden module behind an api dependency fails`() {
        project(bToC = "api")
        val failed = runner(":a:checkModuleGraph").buildAndFail()
        assertTrue(forbidden in failed.output, failed.output)
    }

    @Test
    fun `a forbidden module on the tests' compile classpath fails`() {
        project(bToC = "implementation", aExtra = "    testImplementation(project(\":c\"))\n")
        val failed = runner(":a:checkModuleGraph").buildAndFail()
        assertTrue(forbidden in failed.output, failed.output)
    }
}
