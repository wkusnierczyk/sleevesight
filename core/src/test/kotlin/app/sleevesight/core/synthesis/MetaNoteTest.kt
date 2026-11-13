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
        assertIs<InsightReply.Written>(GroundingCheck.check(reply, IDS, allowModel = false, notes = true)).grounded

    @Test
    fun `the phone's reply drops its note on the source it did not use`() {
        val grounded = written(PHONE)
        assertEquals(PHONE_KEPT, grounded.text)
        assertEquals(GroundingCounts(ok = 2, model = 0, hedge = 0, uncited = 0, unknown = 0), grounded.counts)
        val withdrawn = assertNotNull(grounded.withdrawn)
        assertTrue(withdrawn.startsWith("*One lyrics site"), withdrawn)
        assertTrue(withdrawn.endsWith("[Not usable; omit]*"), withdrawn)
    }

    @Test
    fun `a trailing note worded otherwise, or on a line or paragraph of its own, is dropped`() {
        val notes =
            listOf(
                " [Not usable; omit]",
                " [omit]",
                " A review calls it a ballad, but I can't cite it.",
                " One site dates it to 1984, but I can't cite it. [unsupported]",
                "\nThe evidence does not give the B-side, so I'll omit it.",
                "\n\n*A fan page names the session players, but I can't cite that page.*",
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

    @Test
    fun `a lyric in emphasis inside a sentence is not read, whatever its punctuation`() {
        val reply = "$CLEAN The refrain repeats *the evidence doesn't show you lied...* over two chords."
        assertEquals(reply, written(reply).text)
        assertNull(MetaNote.of(reply))
    }

    @Test
    fun `evidence in another sense is the insight's own`() {
        val reply = "$CLEAN The lyrics treat a ring found in the evidence locker as proof of betrayal."
        assertEquals(reply, written(reply).text)
        assertNull(MetaNote.of(reply))
    }

    @Test
    fun `a section's cue in brackets is no working note`() {
        for (ending in listOf(" [Drop]", " Its last section is marked [Drop].", " [Skip]", " [Chorus 2]")) {
            val reply = CLEAN + ending
            assertNull(MetaNote.of(reply), ending)
            assertEquals(reply, written(reply).text, ending)
        }
    }

    @Test
    fun `emphasis around the note alone goes with it, and marks alone are no reply`() {
        val grounded = written("$CLEAN **[Not usable; omit]**")
        assertEquals(CLEAN, grounded.text)
        assertEquals(0, grounded.counts.uncited)
        assertEquals("**[Not usable; omit]**", grounded.withdrawn)
        assertNull(MetaNote.of("**[Not usable; omit]**"))
    }

    @Test
    fun `a lyric in emphasis over two sentences is not cut at its second`() {
        val reply = "$CLEAN The refrain runs *I'm done. I'll leave out your name.*"
        assertNull(MetaNote.of(reply))
        assertEquals(reply, written(reply).text)
        // The same with the model's words: inside an emphasis opened before it, never taken out.
        val quoting = "$CLEAN The refrain runs *I'm done. I can't quote the evidence.*"
        assertNull(MetaNote.of(quoting))
    }

    @Test
    fun `talk of the evidence not in the first person is the insight's own`() {
        for (ending in listOf(
            " The lyrics say the evidence does not confirm the narrator's guilt.",
            // An accepted miss: a note not in the first person stays, flagged as uncited.
            " The sources don't say who produced it.",
            " One site dates it to 1984, which is not in the evidence.",
        )) {
            val reply = CLEAN + ending
            assertNull(MetaNote.of(reply), ending)
            assertEquals(reply, written(reply).text, ending)
        }
    }

    @Test
    fun `a Roman numeral or a title's I or My is no first person`() {
        for (ending in listOf(
            " Sinatra later cited My Way as the song he grew to dislike.",
            " The coda quotes the opening theme of Part I.",
            " Cobain cited I Wanna Be Your Dog among his favourites.",
            " Led Zeppelin I quotes several blues standards.",
        )) {
            val reply = CLEAN + ending
            assertNull(MetaNote.of(reply), ending)
            assertEquals(reply, written(reply).text, ending)
        }
    }

    @Test
    fun `a stated gap in the first person stays`() {
        for (ending in listOf(
            " Little else is documented in my sources.",
            " I found no further details on the session in the evidence.",
        )) {
            val reply = CLEAN + ending
            assertNull(MetaNote.of(reply), ending)
            assertEquals(reply, written(reply).text, ending)
        }
    }

    @Test
    fun `a note with stray closing marks after it is left as written`() {
        val reply = "$CLEAN [omit]*"
        assertNull(MetaNote.of(reply))
        assertEquals(reply, written(reply).text)
    }

    @Test
    fun `a reply that is no insight's keeps its talk of the evidence`() {
        // A genre's reason may say what the evidence does not support: that is its answer.
        val reason = "$CLEAN The evidence does not support the Alternative label."
        val grounded = assertIs<InsightReply.Written>(GroundingCheck.check(reason, IDS, allowModel = false)).grounded
        assertEquals(reason, grounded.text)
        assertNull(grounded.withdrawn)
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
