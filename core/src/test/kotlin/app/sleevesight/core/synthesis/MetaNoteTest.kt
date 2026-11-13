package app.sleevesight.core.synthesis

import app.sleevesight.fixtures.Fixtures
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A note the model left at its reply's end on its own evidence or work (#1207) is taken out before
 * the check, kept for the developer; an insight's own text is never cut: not a quotation, a title or
 * a lyric holding such words, not a stated gap, not a note in the middle, not a reply of a note alone.
 * The phone's reply is Mr. Mister's "Broken Wings" (Claude Haiku 5.5, 2026-11-11), as written.
 */
class MetaNoteTest {
    private fun written(reply: String): GroundedText =
        assertIs<InsightReply.Written>(GroundingCheck.check(reply, IDS, allowModel = false)).grounded

    @Test
    fun `the phone's reply drops its note on the source it did not use`() {
        val grounded = written(PHONE)
        assertEquals(PHONE_KEPT, grounded.text)
        assertEquals(GroundingCounts(ok = 2, model = 0, hedge = 0, uncited = 0, unknown = 0), grounded.counts)
        val withdrawn = assertNotNull(grounded.withdrawn)
        assertTrue(withdrawn.startsWith("*One lyrics site"), withdrawn)
        assertTrue(withdrawn.endsWith("[Not usable; omit]"), withdrawn)
    }

    @Test
    fun `a trailing note worded otherwise, or on a line or paragraph of its own, is dropped`() {
        val notes =
            listOf(
                " [Not usable; omit]",
                " [omit]",
                " The sources don't say who produced it.",
                " A review calls it a ballad, but I can't cite it.",
                " One site dates it to 1984, which is not in the evidence. [unsupported]",
                "\nThe evidence does not give the B-side, so I'll omit it.",
                "\n\n*A fan page names the session players, but that page is not in the evidence.*",
                " A blog reads it as a farewell, but I can't quote it, since my sources don't hold its text.",
            )
        for (note in notes) {
            val grounded = written(CLEAN + note)
            assertEquals(CLEAN, grounded.text, note)
            assertEquals(0, grounded.counts.uncited, note)
            assertNotNull(grounded.withdrawn, note)
        }
    }

    @Test
    fun `clean replies are left as written`() {
        for (reply in listOf(CLEAN, PHONE_KEPT, "It came out in 1985 [MB1].\n\nIt reached number one [W1].")) {
            val grounded = written(reply)
            assertEquals(reply, grounded.text)
            assertNull(grounded.withdrawn, reply)
        }
    }

    @Test
    fun `such words in a quotation, a title, a lyric or a cited sentence are the insight's own`() {
        val endings =
            listOf(
                // Quoted lyrics, a title in emphasis, a capitalised title: not read.
                " Its last line is \"I can't quote it, it's not in the evidence.\"",
                " Its chorus runs “the evidence says you lied”.",
                " *I Can't Quote It* closed the set.",
                " The title nods to Monk's *Evidence*.",
                " Its B-side was *Not In The Evidence*.",
                // Cited: whatever it says, it is written from a line.
                " A review notes the band can't cite a single influence [W1].",
                // A stated gap is a hedge, uncited on purpose.
                " Little else is documented.",
                " No further details are given in the evidence.",
                // A bracket that is no working note.
                " The single version fades early [Chorus].",
            )
        for (ending in endings) {
            val reply = CLEAN + ending
            val grounded = written(reply)
            assertEquals(reply, grounded.text, ending)
            assertNull(grounded.withdrawn, ending)
        }
    }

    @Test
    fun `a note in the middle, or a reply of a note alone, is left as written`() {
        val middle = "It came out in 1985 [MB1]. That date is not in the evidence. It reached number one [W1]."
        assertEquals(middle, written(middle).text)
        val alone = "The sources don't say who wrote it."
        assertNull(MetaNote.of(alone))
        assertEquals(alone, written(alone).text)
    }

    @Test
    fun `a correction taken out and a note dropped are both withdrawn`() {
        val reply =
            "It came out in 1985 [MB1]. It was a hit everywhere.\n\n" +
                "Wait, that second sentence is not supported by the evidence, so I'll drop it. " +
                "Here is the corrected insight:\n\n" +
                "It came out in 1985 [MB1]. That chart claim is not in the evidence, so I can't cite it."
        val grounded = written(reply)
        assertEquals("It came out in 1985 [MB1].", grounded.text)
        val withdrawn = assertNotNull(grounded.withdrawn)
        assertTrue(withdrawn.startsWith("It came out in 1985 [MB1]. It was a hit everywhere."), withdrawn)
        assertTrue(withdrawn.endsWith("so I can't cite it."), withdrawn)
    }

    /** No stored reply of the grounding check's (`grounding/replies.json`) ends with a note. */
    @Test
    fun `no stored reply is read as ending with a note`() {
        val json = Json { ignoreUnknownKeys = true }
        val replies = json.decodeFromString<List<Stored>>(Fixtures.text("grounding/replies.json"))
        assertTrue(replies.isNotEmpty())
        for (reply in replies) assertNull(MetaNote.of(reply.reply), reply.name)
    }

    @Serializable
    private data class Stored(
        val name: String,
        val reply: String,
    )

    private companion object {
        val IDS = setOf("MB1", "MB2", "MB7", "W1", "W2")

        const val CLEAN = "It came out in 1985 [MB1]. It reached number one [W1]."

        const val PHONE_KEPT =
            "The song was written by Steve George, John Lang and Richard Page [MB2], and Richard Page also " +
                "sings lead and plays bass on this recording [MB7]. It became the first of two consecutive " +
                "American number ones for the band, the other being \"Kyrie\" [W2]."

        const val PHONE =
            PHONE_KEPT +
                " *One lyrics site reads it as a farewell, but the page's wording is not in the evidence, so I " +
                "can't quote it. [Not usable; omit]*"
    }
}
