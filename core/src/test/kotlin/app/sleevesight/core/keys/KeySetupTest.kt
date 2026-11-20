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
    private val google = "AIza" + "Sy0aB1-cD2_".repeat(3) + "eF"
    private val exa = "0c6d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f"
    private val lastFm = "0123456789abcdef0123456789ABCDEF"

    private fun setup(text: String) = assertIs<SetupRead.Setup>(KeySetup.read(text))

    @Test
    fun `a setup code's keys are read in order, each with its service`() {
        val read =
            setup(
                "sleevesight-keys:v1\nanthropic=$anthropic\r\nopenai=$openAi\ngoogle=$google\n" +
                    "exa = $exa \n\nlastfm=$lastFm\n",
            )
        assertEquals(
            listOf(KeyName.ANTHROPIC, KeyName.OPENAI, KeyName.GOOGLE, KeyName.EXA, KeyName.LASTFM),
            read.keys.keys.toList(),
        )
        assertEquals(anthropic, read.keys.getValue(KeyName.ANTHROPIC).reveal())
        assertEquals(openAi, read.keys.getValue(KeyName.OPENAI).reveal())
        assertEquals(google, read.keys.getValue(KeyName.GOOGLE).reveal())
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
    fun `a key of the wrong shape is refused with the reason, and only its ending is kept`() {
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
        // Only the last four characters of a refused key, to name it by (#763); none of an empty one.
        assertEquals(
            mapOf(KeyName.ANTHROPIC to lastFm.takeLast(4), KeyName.LASTFM to "xtra"),
            read.refusedEndings,
        )
    }

    @Test
    fun `a refused key no longer than its ending, or named twice, keeps no ending`() {
        val read = setup("sleevesight-keys:v1\nanthropic=a b\nlastfm=$lastFm\nlastfm=${lastFm.lowercase()}")
        assertEquals(setOf(KeyName.ANTHROPIC, KeyName.LASTFM), read.refused.keys)
        assertTrue(read.refusedEndings.isEmpty())
    }

    @Test
    fun `unknown names are reported, cut short, and unreadable lines counted`() {
        val read = setup("sleevesight-keys:v1\nmistral=x\n${"n".repeat(40)}=v\njust-a-line\n=nameless\nexa=$exa")
        assertEquals(listOf("mistral", "n".repeat(24)), read.unknown)
        assertEquals(2, read.unreadable)
        assertEquals(setOf(KeyName.EXA), read.keys.keys)
    }

    @Test
    fun `a refused key of four characters keeps no ending, one of five keeps its last four`() {
        val read = setup("sleevesight-keys:v1\nanthropic=abcd\nopenai=abcde")
        assertEquals(setOf(KeyName.ANTHROPIC, KeyName.OPENAI), read.refused.keys)
        assertEquals(mapOf(KeyName.OPENAI to "bcde"), read.refusedEndings)
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
        assertEquals(SetupRead.Unsupported("v3"), KeySetup.read("sleevesight-keys:v3\nanthropic=$anthropic"))
        assertEquals("v3", assertIs<SetupRead.Unsupported>(KeySetup.read("sleevesight-keys:v3")).version)
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
        assertNull(KeyShape.check(KeyName.OPENAI, "sk-svcacct-" + "a1B2_c3-D4".repeat(12)))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.OPENAI, anthropic))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.OPENAI, "sk-proj-short"))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.OPENAI, exa))
        assertEquals(39, google.length)
        assertNull(KeyShape.check(KeyName.GOOGLE, google))
        assertNull(KeyShape.check(KeyName.GOOGLE, "AQ.Ab8RN6" + "Lq0_x-Yz.9".repeat(5)))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.GOOGLE, anthropic))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.GOOGLE, "AIzaShort"))
        assertEquals(KeyProblem.BAD_CHARACTERS, KeyShape.check(KeyName.GOOGLE, "$google "))
        assertEquals(KeyProblem.BAD_CHARACTERS, KeyShape.check(KeyName.OPENAI, "$openAi."))
        assertNull(KeyShape.check(KeyName.EXA, exa))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.EXA, "short"))
        assertEquals(KeyProblem.BAD_CHARACTERS, KeyShape.check(KeyName.EXA, "$exa\n"))
        assertNull(KeyShape.check(KeyName.LASTFM, lastFm))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.LASTFM, lastFm.drop(1)))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.LASTFM, "g" + lastFm.drop(1)))
    }

    @Test
    fun `each service's key is accepted at its shortest and longest, and refused one past either`() {
        // [prefix] padded with key characters to [length].
        fun key(
            prefix: String,
            length: Int,
        ) = prefix + "a1B2_c3-D4".repeat(41).take(length - prefix.length)
        val bounds =
            listOf(
                Triple(KeyName.OPENROUTER, "sk-or-v1-", 40..400),
                Triple(KeyName.ANTHROPIC, "sk-ant-", 40..400),
                Triple(KeyName.OPENAI, "sk-proj-", 40..400),
                Triple(KeyName.GOOGLE, "AIza", 30..400),
                Triple(KeyName.GOOGLE, "AQ.", 30..400),
                Triple(KeyName.EXA, "", 16..200),
            )
        for ((name, prefix, lengths) in bounds) {
            assertNull(KeyShape.check(name, key(prefix, lengths.first)), "$name, ${lengths.first}")
            assertNull(KeyShape.check(name, key(prefix, lengths.last)), "$name, ${lengths.last}")
            assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(name, key(prefix, lengths.first - 1)), "$name, short")
            assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(name, key(prefix, lengths.last + 1)), "$name, long")
        }
    }

    @Test
    fun `an OpenRouter key starts sk-or-, and OpenAI's and Anthropic's are not one`() {
        val openRouter = "sk-or-v1-" + "0123456789abcdef".repeat(4)
        assertNull(KeyShape.check(KeyName.OPENROUTER, openRouter))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.OPENROUTER, openAi))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.OPENROUTER, anthropic))
        assertEquals(KeyProblem.WRONG_SHAPE, KeyShape.check(KeyName.OPENAI, openRouter))
        assertEquals(KeyProblem.BAD_CHARACTERS, KeyShape.check(KeyName.OPENROUTER, "$openRouter."))
    }
}
