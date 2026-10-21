package app.sleevesight.cli

import app.sleevesight.core.model.ProviderId
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
            assertFalse("from-env" in keys.exa.toString())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `each AI service's key is read from its own variable`() {
        val file = File.createTempFile("keys", ".env").apply { writeText("SLEEVESIGHT_OPENAI_API_KEY=sk-proj-1\n") }
        try {
            val keys = Keys.load(mapOf("SLEEVESIGHT_ANTHROPIC_API_KEY" to "sk-ant-1"), file)
            assertEquals("sk-ant-1", keys.ai(ProviderId.ANTHROPIC)?.reveal())
            assertEquals("sk-proj-1", keys.ai(ProviderId.OPENAI)?.reveal())
            assertNull(keys.ai(ProviderId.GOOGLE))
            assertEquals("SLEEVESIGHT_OPENAI_API_KEY", Keys.aiVariable(ProviderId.OPENAI))
        } finally {
            file.delete()
        }
    }
}
