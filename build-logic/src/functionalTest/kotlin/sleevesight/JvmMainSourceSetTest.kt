package sleevesight

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `sleevesight.jvm-main` on a small two-module build: portable code cannot see `jvmMain`, while
 * `jvmMain` sees `main`'s internals; the module's tests and test fixtures see the internals of
 * both; its jar and a consumer carry both; and `jvmMain` resolves exactly `main`'s dependencies.
 *
 * A functional test (`./gradlew :build-logic:functionalTest`, run by CI after the build): the
 * nested build resolves Kotlin from Maven Central, so it stays out of `check`.
 */
class JvmMainSourceSetTest {
    @TempDir
    lateinit var dir: File

    private fun project() {
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
        dir.write("lib/build.gradle.kts", "plugins {\n    id(\"sleevesight.jvm-main\")\n    `java-test-fixtures`\n}\n")
        dir.write(
            "lib/src/main/kotlin/p/Hasher.kt",
            "package p\n\nfun interface Hasher { fun hash(s: String): String }\n\ninternal fun secret(): String = \"s\"\n",
        )
        dir.write(
            "lib/src/jvmMain/kotlin/p/platform/JvmHasher.kt",
            "package p.platform\n\nimport p.Hasher\nimport p.secret\n\n" +
                "object JvmHasher : Hasher { override fun hash(s: String): String = s + secret() }\n\n" +
                "internal fun platformSecret(): String = \"j\"\n",
        )
        dir.write(
            "lib/src/test/kotlin/p/HasherTest.kt",
            "package p\n\nimport p.platform.platformSecret\n\n" +
                "val tested: Hasher = p.platform.JvmHasher\nval mainInternal = secret()\nval jvmInternal = platformSecret()\n",
        )
        dir.write(
            "lib/src/testFixtures/kotlin/p/Fixture.kt",
            "package p\n\nimport p.platform.platformSecret\n\n" +
                "val fixture: Hasher = p.platform.JvmHasher\nval fixtureMain = secret()\nval fixtureJvm = platformSecret()\n",
        )
        dir.write(
            "consumer/build.gradle.kts",
            "plugins { kotlin(\"jvm\") }\n\ndependencies { implementation(project(\":lib\")) }\n",
        )
        dir.write("consumer/src/main/kotlin/c/Use.kt", "package c\n\nval used: p.Hasher = p.platform.JvmHasher\n")
    }

    private fun File.write(
        path: String,
        text: String,
    ) = resolve(path).apply { parentFile.mkdirs() }.writeText(text)

    private fun runner(vararg tasks: String) =
        GradleRunner
            .create()
            .withProjectDir(dir)
            .withTestKitDir(File(System.getProperty("testkit.dir")))
            .withPluginClasspath()
            .withArguments(*tasks, "--stacktrace")

    @Test
    fun `jvmMain is separate from main and carried by the module`() {
        project()
        runner(
            ":lib:compileTestKotlin",
            ":lib:compileTestFixturesKotlin",
            ":lib:jar",
            ":consumer:compileKotlin",
            ":lib:checkSourceRoots",
        ).build()
        val jar = dir.resolve("lib/build/libs").listFiles()!!.single { it.name == "lib.jar" }
        ZipFile(jar).use { zip ->
            assertNotNull(zip.getEntry("p/platform/JvmHasher.class"), "JvmHasher in the jar")
            assertNotNull(zip.getEntry("p/Hasher.class"), "Hasher in the jar")
        }
    }

    @Test
    fun `portable code that refers to a JVM implementation does not compile`() {
        project()
        dir.write("lib/src/main/kotlin/p/Sneaky.kt", "package p\n\nval sneaky: Hasher = p.platform.JvmHasher\n")
        val failed = runner(":lib:compileKotlin").buildAndFail()
        assertTrue("Unresolved reference" in failed.output, failed.output)
    }

    @Test
    fun `nothing compiles from src main java`() {
        project()
        dir.write("lib/src/main/java/p/Hidden.kt", "package p\n\nobject Hidden\n")
        val failed = runner(":lib:checkSourceRoots").buildAndFail()
        assertTrue("Hidden.kt" in failed.output, failed.output)
    }

    // `lib` with a dependency of `main`'s, and [extra] appended to its build script.
    private fun libWith(extra: String) {
        dir.write(
            "lib/build.gradle.kts",
            "plugins { id(\"sleevesight.jvm-main\") }\n\n" +
                "dependencies { implementation(\"org.jetbrains:annotations:23.0.0\") }\n\n" + extra,
        )
    }

    private fun assertCheckFailsNaming(component: String) {
        val failed = runner(":lib:check").buildAndFail()
        assertTrue("jvmMain shares main's dependencies" in failed.output, failed.output)
        assertTrue(component in failed.output, failed.output)
    }

    @Test
    fun `jvmMain compiles on its own, and shares main's dependencies`() {
        project()
        libWith("")
        runner(":lib:compileJvmMainKotlin").build()
        runner(":lib:checkJvmMainDependencies").build()
    }

    @Test
    fun `a dependency declared on jvmMain fails check`() {
        project()
        libWith("dependencies { \"jvmMainImplementation\"(\"org.jspecify:jspecify:1.0.0\") }\n")
        assertCheckFailsNaming("org.jspecify:jspecify:1.0.0")
    }

    @Test
    fun `a dependency inherited by jvmMain fails check`() {
        project()
        libWith(
            "val extra = configurations.create(\"extra\")\n" +
                "configurations.named(\"jvmMainImplementation\") { extendsFrom(extra) }\n" +
                "dependencies { \"extra\"(\"org.jspecify:jspecify:1.0.0\") }\n",
        )
        assertCheckFailsNaming("org.jspecify:jspecify:1.0.0")
    }

    @Test
    fun `a constraint that raises a version for jvmMain fails check`() {
        project()
        libWith("dependencies { constraints { add(\"jvmMainImplementation\", \"org.jetbrains:annotations:24.0.0\") } }\n")
        assertCheckFailsNaming("org.jetbrains:annotations:24.0.0")
    }
}
