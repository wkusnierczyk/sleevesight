package sleevesight

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The functional tests' nested builds check what they download as the main build does (#1034;
 * `Probe`). A functional test (`./gradlew :build-logic:functionalTest`): it needs Maven Central.
 */
class ProbeTest {
    @TempDir
    lateinit var dir: File

    @AfterEach
    fun collectVerification() = Probe.collect(dir)

    @Test
    fun `a nested build refuses a file the repository's verification metadata does not cover`() {
        // While the metadata is being written, the build would record the file rather than refuse it.
        assumeFalse(Probe.writing)
        dir.resolve("settings.gradle.kts").writeText(
            "dependencyResolutionManagement { repositories { mavenCentral() } }\nrootProject.name = \"probe\"\n",
        )
        // A version no build here uses, so it has no checksum, and no key is trusted for it.
        dir.resolve("build.gradle.kts").writeText(
            "val unrecorded = configurations.create(\"unrecorded\")\n" +
                "dependencies { unrecorded(\"org.jetbrains:annotations:22.0.0\") }\n" +
                "tasks.register(\"resolve\") { doLast { unrecorded.files } }\n",
        )
        val failed = Probe.runner(dir, "resolve").buildAndFail()
        assertTrue("Dependency verification failed" in failed.output, failed.output)
    }
}
