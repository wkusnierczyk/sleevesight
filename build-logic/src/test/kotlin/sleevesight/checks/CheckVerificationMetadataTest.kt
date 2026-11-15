package sleevesight.checks

import kotlin.test.Test
import kotlin.test.assertEquals

class CheckVerificationMetadataTest {
    private val metadata =
        """
        <verification-metadata>
           <components>
              <component group="org.example" name="lib" version="1.0">
                 <artifact name="lib-1.0.jar"><sha256 value="00"/></artifact>
              </component>
              <component group="org.example" name="tool" version="2.1">
              </component>
           </components>
        </verification-metadata>
        """.trimIndent()

    private val header =
        "# This is a Gradle generated file for dependency locking.\n# Manual edits can break the build.\n"

    @Test
    fun `every locked version recorded passes`() {
        val lockfile = header + "org.example:lib:1.0=runtimeClasspath\norg.example:tool:2.1=ktlint\nempty=annotationProcessor\n"
        assertEquals(emptyList(), CheckVerificationMetadata.missing(listOf("app/gradle.lockfile" to lockfile), metadata))
    }

    @Test
    fun `a version with no entry is named with the lockfiles that lock it`() {
        val app = header + "org.example:lib:1.1=runtimeClasspath,compileClasspath\norg.example:tool:2.1=ktlint\n"
        val core = header + "org.example:lib:1.1=runtimeClasspath\n"
        assertEquals(
            listOf("org.example:lib:1.1 (app/gradle.lockfile, core/gradle.lockfile)"),
            CheckVerificationMetadata.missing(listOf("app/gradle.lockfile" to app, "core/gradle.lockfile" to core), metadata),
        )
    }

    @Test
    fun `comments, blank lines and the empty line are not versions`() {
        val lockfile = header + "\nempty=classpath\n"
        assertEquals(emptyList(), CheckVerificationMetadata.missing(listOf("buildscript-gradle.lockfile" to lockfile), metadata))
    }
}
