package sleevesight

import org.gradle.testkit.runner.GradleRunner
import java.io.File

/**
 * The nested builds the functional tests run, each in a project of its own. Each gets the
 * repository's dependency verification (#1034): `gradle/verification-metadata.xml` and the keyring,
 * so the Kotlin compiler and libraries it downloads into the TestKit home are checked as the main
 * build's are.
 *
 * With `SLEEVESIGHT_WRITE_VERIFICATION_METADATA` set to a mode (`pgp,sha256`, `sha256`), as
 * `tools/refresh-dependency-verification.sh` sets it, each nested build writes the metadata instead
 * of checking it, and [collect] brings it back to the repository's for the next to build on, so the
 * repository's file covers what the nested builds resolve too. The tests run one at a time.
 */
object Probe {
    private val verificationDir = File(System.getProperty("verification.dir"))
    private val verificationFiles = listOf("verification-metadata.xml", "verification-keyring.keys")
    private val writeMode = System.getenv("SLEEVESIGHT_WRITE_VERIFICATION_METADATA")?.takeIf { it.isNotBlank() }

    /** Whether the nested builds write the metadata rather than check it. */
    val writing: Boolean get() = writeMode != null

    /** A runner for the project in [dir], with the verification files copied in on its first run. */
    fun runner(
        dir: File,
        vararg arguments: String,
    ): GradleRunner {
        val target = dir.resolve("gradle").apply { mkdirs() }
        verificationFiles.forEach { name ->
            val copy = target.resolve(name)
            if (!copy.exists()) verificationDir.resolve(name).copyTo(copy)
        }
        val write = writeMode?.let { listOf("--write-verification-metadata", it, "--export-keys") }.orEmpty()
        return GradleRunner
            .create()
            .withProjectDir(dir)
            .withTestKitDir(File(System.getProperty("testkit.dir")))
            .withPluginClasspath()
            .withArguments(*arguments, *write.toTypedArray(), "--stacktrace")
    }

    /** When writing the metadata, copies what the nested builds in [dir] wrote back to the repository. */
    fun collect(dir: File) {
        if (writeMode == null) return
        verificationFiles.forEach { name ->
            dir.resolve("gradle/$name").takeIf { it.exists() }?.copyTo(verificationDir.resolve(name), overwrite = true)
        }
    }
}
