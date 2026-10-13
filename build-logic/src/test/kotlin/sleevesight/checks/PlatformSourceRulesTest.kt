package sleevesight.checks

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformSourceRulesTest {
    private val network = listOf("java.net.", "javax.net.", "okhttp3.", "io.ktor.client.")

    private fun portable(source: String) = PlatformSourceRules.portableProblems("Main.kt", source)

    private fun platform(source: String) = PlatformSourceRules.platformProblems("Jvm.kt", source, network)

    private val corePackage = "package io.github.wkusnierczyk.sleevesight.core.evidence\n\n"
    private val platformPackage = "package io.github.wkusnierczyk.sleevesight.core.platform\n\n"

    @Test
    fun `portable code may not refer to a platform package, however it is written`() {
        val references =
            mapOf(
                "explicit import" to "import io.github.wkusnierczyk.sleevesight.core.platform.JvmHasher\n",
                "star import" to "import io.github.wkusnierczyk.sleevesight.core.platform.*\n",
                "fully qualified" to "val h = io.github.wkusnierczyk.sleevesight.core.platform.JvmHasher\n",
                "backticked" to "import io.github.wkusnierczyk.sleevesight.core.`platform`.JvmAccentFolder\n",
                "spaced dots" to "val h = io.github.wkusnierczyk.sleevesight.core . platform.JvmHasher\n",
                "a platform package itself" to "package io.github.wkusnierczyk.sleevesight.core.platform\n",
            )
        for ((case, source) in references) {
            assertEquals(1, portable(corePackage + source).size, case)
        }
    }

    @Test
    fun `comments and strings in portable code are not references`() {
        val source =
            corePackage +
                "// see io.github.wkusnierczyk.sleevesight.core.platform.JvmHasher\n" +
                "/* io.github.wkusnierczyk.sleevesight.core.platform */\n" +
                "val s = \"io.github.wkusnierczyk.sleevesight.core.platform\"\n" +
                "val t = \"\"\"io.github.wkusnierczyk.sleevesight.core.platform\"\"\"\n" +
                "val platform = 1\n"
        assertEquals(emptyList(), portable(source))
    }

    @Test
    fun `platform code must be in a platform package`() {
        val outside = corePackage + "object JvmSneaky\n"
        assertEquals(1, platform(outside).size)
        assertTrue(platform(outside).single().contains("must be in a `platform` package"))
        assertEquals(1, platform("object NoPackage\n").size)
        assertEquals(emptyList(), platform(platformPackage + "object JvmFine\n"))
        assertEquals(
            emptyList(),
            platform("package io.github.wkusnierczyk.sleevesight.core.platform.jvm\n\nobject Deeper\n"),
        )
    }

    @Test
    fun `platform code may not use the network APIs, imported or written out`() {
        val uses =
            mapOf(
                "import" to "import java.net.URL\n",
                "star import" to "import java.net.*\n",
                "fully qualified" to "fun f() = java.net.URL(\"https://example.org\").readText()\n",
                "backticked" to "fun f() = java.`net`.URL(\"https://example.org\")\n",
                "OkHttp" to "import okhttp3.OkHttpClient\n",
                "Ktor" to "import io.ktor.client.HttpClient\n",
            )
        for ((case, source) in uses) {
            assertEquals(1, platform(platformPackage + source).size, case)
        }
        assertEquals(emptyList(), platform(platformPackage + "import java.security.MessageDigest\n// java.net.URL\n"))
    }

    @Test
    fun `the existing sources of core pass`() {
        val core = File("../core/src")
        val platformFiles = File(core, "jvmMain/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        val portableFiles = File(core, "main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(platformFiles.map { it.name }.containsAll(listOf("JvmHasher.kt", "JvmAccentFolder.kt")))
        assertTrue(portableFiles.isNotEmpty())
        for (file in platformFiles) {
            assertEquals(emptyList(), PlatformSourceRules.platformProblems(file.path, file.readText(), network), file.name)
        }
        for (file in portableFiles) {
            assertEquals(emptyList(), PlatformSourceRules.portableProblems(file.path, file.readText()), file.name)
        }
    }
}
