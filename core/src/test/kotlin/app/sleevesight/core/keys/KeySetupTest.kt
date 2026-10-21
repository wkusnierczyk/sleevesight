package app.sleevesight.core.keys

import app.sleevesight.core.Secret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeySetupTest {
    private val anthropic = "sk-ant-api03-" + "a1B2_c3-D4".repeat(9)
    private val openAi = "sk-proj-" + "e5F6_g7-H8".repeat(15)
    private val exa = "0c6d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f"
    private val lastFm = "0123456789abcdef0123456789ABCDEF"

    private fun setup(text: String) = assertIs<SetupRead.Setup>(KeySetup.read(text))

    @Test
    fun `a setup code's keys are read in order, each with its service`() {
        val read =
            setup("sleevesight-keys:v1\nanthropic=$anthropic\r\nopenai=$openAi\nexa = $exa \n\nlastfm=$lastFm\n")
        assertEquals(
            listOf(KeyName.ANTHROPIC, KeyName.OPENAI, KeyName.EXA, KeyName.LASTFM),
            read.keys.keys.toList(),
        )
        assertEquals(anthropic, read.keys.getValue(KeyName.ANTHROPIC).reveal())
        assertEquals(openAi, read.keys.getValue(KeyName.OPENAI).reveal())
        assertEquals(exa, read.keys.getValue(KeyName.EXA).reveal())
        assertEquals(lastFm, read.keys.getValue(KeyName.LASTFM).reveal())
        assertTrue(read.refused.isEmpty())
        assertTrue(read.unknown.isEmpty())
        assertEquals(0, read.unreadable)
    }

    @Test
    fun `what the CLI writes reads back the same`() {
        val keys = mapOf(KeyName.LASTFM to Secret(lastFm), KeyName.ANTHROPIC to Secret(anthropic))
        val text = KeySetup.write(keys)
        assertEquals("sleevesight-keys:v1\nanthropic=$anthropic\nlastfm=$lastFm", text)
        val read = setup(text)
        assertEquals(keys.mapValues { it.value.reveal() }, read.keys.mapValues { it.value.reveal() })
    }

    @Test
    fun `a key of the wrong shape is refused with the reason, and its value is not kept`() {
        val read =
            setup(
                "sleevesight-keys:v1\nanthropic=sk-proj-$lastFm$lastFm\nexa=\nlastfm=$lastFm extra\n",
            )
        assertTrue(read.keys.isEmpty())
        assertEquals(
            mapOf(
                KeyName.ANTHROPIC to KeyProblem.WRONG_SHAPE,
                KeyName.EXA to KeyProblem.EMPTY,
                KeyName.LASTFM to KeyProblem.BAD_CHARACTERS,
            ),
            read.refused,
        )
    }

    @Test
    fun `unknown names are reported, cut short, and unreadable lines counted`() {
        val read = setup("sleevesight-keys:v1\nmistral=x\n${"n".repeat(40)}=v\njust-a-line\n=nameless\nexa=$exa")
        assertEquals(listOf("mistral", "n".repeat(24)), read.unknown)
        assertEquals(2, read.unreadable)
        assertEquals(setOf(KeyName.EXA), read.keys.keys)
    }

    @Test
    fun `a name given twice is refused, whichever value was meant`() {
        val read = setup("sleevesight-keys:v1\nlastfm=$lastFm\nlastfm=${lastFm.lowercase()}\nexa=$exa")
        assertEquals(setOf(KeyName.EXA), read.keys.keys)
        assertEquals(mapOf(KeyName.LASTFM to KeyProblem.DUPLICATE), read.refused)
    }

    @Test
    fun `text without the first line is not a setup code, and a later version is told apart`() {
        assertEquals(SetupRead.NotASetup, KeySetup.read(anthropic))
        assertEquals(SetupRead.NotASetup, KeySetup.read(""))
        assertEquals(SetupRead.NotASetup, KeySetup.read("anthropic=$anthropic"))
        assertEquals(SetupRead.Unsupported("v2"), KeySetup.read("sleevesight-keys:v2\nanthropic=$anthropic"))
        assertTrue(KeySetup.isSetup("  sleevesight-keys:v1\n"))
        assertFalse(KeySetup.isSetup(anthropic))
    }

    @Test
    fun `each service's shape`() {
        assertNull(KeyShape.check(KeyName.ANTHROPIC, anthropic))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.ANTHROPIC, "sk-ant-short"))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.ANTHROPIC, exa))
        assertNull(KeyShape.check(KeyName.OPENAI, openAi))
        assertNull(KeyShape.check(KeyName.OPENAI, "sk-" + "a1B2c3D4".repeat(6)))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.OPENAI, anthropic))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.OPENAI, "sk-proj-short"))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.OPENAI, exa))
        assertNull(KeyShape.check(KeyName.EXA, exa))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.EXA, "short"))
        assertEquals(KeyProblem.BAD_CHARACTERS, KeyShape.check(KeyName.EXA, "$exa\n"))
        assertNull(KeyShape.check(KeyName.LASTFM, lastFm))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.LASTFM, lastFm.drop(1)))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.LASTFM, "g" + lastFm.drop(1)))
    }
}
