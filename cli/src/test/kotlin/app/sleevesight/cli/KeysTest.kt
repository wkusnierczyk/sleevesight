package app.sleevesight.cli

import app.sleevesight.core.model.ProviderId
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeysTest {
    @Test
    fun `an env file's assignments are read, with export and quotes`() {
        val parsed =
            Keys.parse(
                listOf(
                    "# keys",
                    "",
                    "export SLEEVESIGHT_EXA_API_KEY=\"exa-1\"",
                    "SLEEVESIGHT_ANTHROPIC_API_KEY='sk-2'",
                    "junk",
                ),
            )
        assertEquals(mapOf("SLEEVESIGHT_EXA_API_KEY" to "exa-1", "SLEEVESIGHT_ANTHROPIC_API_KEY" to "sk-2"), parsed)
    }

    @Test
    fun `the environment wins over the file, and a key is never shown`() {
        val file = File.createTempFile("keys", ".env").apply { writeText("SLEEVESIGHT_EXA_API_KEY=from-file\n") }
        try {
            val keys = Keys.load(mapOf("SLEEVESIGHT_EXA_API_KEY" to "from-env"), file)
            assertEquals("from-env", keys.exa?.reveal())
            assertNull(keys.anthropic)
            assertNull(keys.google)
            assertFalse("from-env" in keys.exa.toString())
        } finally {
            file.delete()
        }
    }

    // [text] as a keys file, loaded with [environment].
    private fun loaded(
        text: String,
        environment: Map<String, String> = emptyMap(),
    ): Keys {
        val file = File.createTempFile("keys", ".env").apply { writeText(text) }
        try {
            return Keys.load(environment, file)
        } finally {
            file.delete()
        }
    }

    private fun Keys.byCode(): Map<String, String> =
        mapOf("anthropic" to anthropic, "openai" to openAi, "google" to google, "exa" to exa, "lastfm" to lastFm)
            .mapNotNull { (code, key) -> key?.let { code to it.reveal() } }
            .toMap()

    @Test
    fun `a keys file may name each key by the provider's usual name`() {
        val keys =
            loaded(
                "ANTHROPIC_API_KEY=a\nOPENAI_API_KEY=o\nGEMINI_API_KEY=g\nEXA_API_KEY=e\nexport LASTFM_API_KEY=\"l\"\n",
            )
        val expected = mapOf("anthropic" to "a", "openai" to "o", "google" to "g", "exa" to "e", "lastfm" to "l")
        assertEquals(expected, keys.byCode())
        assertEquals("g2", loaded("GOOGLE_API_KEY=g2\n").google?.reveal())
    }

    @Test
    fun `in a keys file the prefixed name wins, then Gemini's over Google's, whatever the order`() {
        val keys =
            loaded(
                "ANTHROPIC_API_KEY=usual\nSLEEVESIGHT_ANTHROPIC_API_KEY=prefixed\n" +
                    "GOOGLE_API_KEY=google\nGEMINI_API_KEY=gemini\n" +
                    "EXA_API_KEY=usual\nSLEEVESIGHT_EXA_API_KEY=\n",
            )
        assertEquals("prefixed", keys.anthropic?.reveal())
        assertEquals("gemini", keys.google?.reveal())
        // An empty value is no key, so the next name's is taken.
        assertEquals("usual", keys.exa?.reveal())
        val google = loaded("GEMINI_API_KEY=gemini\nSLEEVESIGHT_GOOGLE_API_KEY=prefixed\nGOOGLE_API_KEY=google\n")
        assertEquals("prefixed", google.google?.reveal())
    }

    @Test
    fun `a later empty value does not replace a key given before it under the same name`() {
        val parsed = Keys.parse(listOf("SLEEVESIGHT_EXA_API_KEY=prefixed", "SLEEVESIGHT_EXA_API_KEY=", "B=1", "B=2"))
        assertEquals(mapOf("SLEEVESIGHT_EXA_API_KEY" to "prefixed", "B" to "2"), parsed)
        val keys = loaded("SLEEVESIGHT_EXA_API_KEY=prefixed\nSLEEVESIGHT_EXA_API_KEY=''\nEXA_API_KEY=usual\n")
        assertEquals("prefixed", keys.exa?.reveal())
    }

    @Test
    fun `the help lists every name a keys file may use`() {
        for (name in Keys.FILE_NAMES.values.flatten()) assertTrue(name in KeysQr.USAGE, name)
        assertTrue(KeysQr.USAGE in Options.USAGE)
    }

    @Test
    fun `the environment is read by the prefixed names only`() {
        val usual =
            mapOf(
                "ANTHROPIC_API_KEY" to "a",
                "OPENAI_API_KEY" to "o",
                "GEMINI_API_KEY" to "g",
                "GOOGLE_API_KEY" to "g",
                "EXA_API_KEY" to "e",
                "LASTFM_API_KEY" to "l",
            )
        assertEquals(emptyMap(), loaded("", usual).byCode())
        // An empty prefixed variable is no key: it hides none of the file's names.
        val empty = loaded("EXA_API_KEY=from-file\n", mapOf("SLEEVESIGHT_EXA_API_KEY" to " "))
        assertEquals(mapOf("exa" to "from-file"), empty.byCode())
        // The environment's prefixed key wins over the file's, under either name.
        val keys = loaded("EXA_API_KEY=from-file\n", usual + ("SLEEVESIGHT_EXA_API_KEY" to "from-env"))
        assertEquals(mapOf("exa" to "from-env"), keys.byCode())
    }

    @Test
    fun `the CLI reads a keys file by the API keys page's names, and finds what the page finds`() {
        val vectors =
            Properties().apply {
                val path =
                    requireNotNull(System.getProperty("sleevesight.apiKeysVectors")) {
                        "run through Gradle: sleevesight.apiKeysVectors not set"
                    }
                File(path).inputStream().use { load(it) }
            }

        fun text(hex: String) =
            hex
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
        val names =
            vectors
                .stringPropertyNames()
                .filter { it.startsWith("names.") }
                .associate { it.removePrefix("names.") to vectors.getProperty(it).split(",") }
        assertEquals(Keys.FILE_NAMES, names)
        val count = vectors.getProperty("files").toInt()
        for (i in 0 until count) {
            val expected =
                vectors
                    .getProperty("file.$i.keys")
                    .split(",")
                    .filter { it.isNotEmpty() }
                    .associate { it.substringBefore(':') to text(it.substringAfter(':')) }
            assertEquals(expected, loaded(text(vectors.getProperty("file.$i.text"))).byCode(), "file $i")
        }
    }

    @Test
    fun `each AI service's key is read from its own variable`() {
        val file =
            File.createTempFile("keys", ".env").apply {
                writeText("SLEEVESIGHT_OPENAI_API_KEY=sk-proj-1\nSLEEVESIGHT_GOOGLE_API_KEY=g-1\n")
            }
        try {
            val keys = Keys.load(mapOf("SLEEVESIGHT_ANTHROPIC_API_KEY" to "sk-ant-1"), file)
            assertEquals("sk-ant-1", keys.forProvider(ProviderId.ANTHROPIC)?.reveal())
            assertEquals("sk-proj-1", keys.forProvider(ProviderId.OPENAI)?.reveal())
            assertEquals("g-1", keys.forProvider(ProviderId.GOOGLE)?.reveal())
            assertNull(keys.forProvider(ProviderId.OLLAMA))
            assertEquals("SLEEVESIGHT_OPENAI_API_KEY", Keys.variableOf(ProviderId.OPENAI))
            assertEquals("SLEEVESIGHT_GOOGLE_API_KEY", Keys.variableOf(ProviderId.GOOGLE))
        } finally {
            file.delete()
        }
    }
}
