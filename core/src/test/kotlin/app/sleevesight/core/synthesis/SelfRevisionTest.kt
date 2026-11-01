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
 * A reply that still revises itself in what the card would show (#798) is found, read after
 * [SelfCorrection] took out what it could; an insight's own text is not: not a quoted or
 * emphasised title or lyric that opens with "Wait", not a stated gap, not prose that goes on from
 * its opening. Measured on the CLI's recorded replies of #399's and #424's runs (917 distinct
 * replies, the same as `SelfCorrectionTest`'s), it finds two, both corrections left as written.
 */
class SelfRevisionTest {
    // What the card would show of [reply]: the grounding check's text, after `SelfCorrection`.
    private fun shown(reply: String): String =
        assertIs<InsightReply.Written>(GroundingCheck.check(reply, IDS, allowModel = false)).grounded.text

    private fun sign(reply: String): SelfRevision? = SelfRevision.of(shown(reply))

    @Test
    fun `the phone's reply is found, as SelfCorrection leaves it as written`() {
        // The comment cites lines, so SelfCorrection does not take it out.
        assertEquals(PHONE.trim(), shown(PHONE))
        assertEquals(SelfRevision.ASIDE, sign(PHONE))
    }

    @Test
    fun `each sign is found on its own`() {
        val draft = "It came out in 1965 [MB1]. It sets the album's tone [W1]."
        assertEquals(SelfRevision.ASIDE, sign("$draft\n\nHmm, that is not right [MB1]."))
        assertEquals(SelfRevision.ASIDE, sign("$draft (Oops, I was wrong.)"))
        assertEquals(SelfRevision.ASIDE, sign("It is the opening song on... no, it is the closer [MB1]."))
        // A correction set as a header in emphasis, read before emphasis is taken out.
        for (header in listOf("**Correction:**", "*Wait,*", "_Update:_", "**Correction**:", "Update:")) {
            val reply = "It came out in 1974 [MB1].\n\n$header It came out in 1975 [MB1]."
            assertEquals(SelfRevision.ASIDE, sign(reply), header)
        }
        assertEquals(SelfRevision.ASIDE, sign("It came out in 1974 [MB1]. **Correction:** It came out in 1975 [MB1]."))
        assertEquals(SelfRevision.FIRST_PERSON, sign("$draft So I'll keep only the release [MB1]."))
        assertEquals(SelfRevision.FIRST_PERSON, sign("$draft Let me correct the second one [MB1]."))
        assertEquals(SelfRevision.FIRST_PERSON, sign("$draft My earlier draft said more [MB1]."))
        assertEquals(SelfRevision.EVIDENCE, sign("$draft That is not supported by the evidence [MB1]."))
        assertEquals(SelfRevision.EVIDENCE, sign("$draft The evidence says that it was 1965 [MB1]."))
        assertEquals(SelfRevision.EVIDENCE, sign("$draft It isn't supported by the sources [W1]."))
        assertEquals(
            SelfRevision.REPEATED_OPENING,
            sign(
                "Basia Bulat's \"Already Forgiven\" was first released as a single in February 2020 [MB1].\n\n" +
                    "Basia Bulat's \"Already Forgiven\" was first released as a single in February 2020 [MB1]. " +
                    "It sits on the album \"Are You in Love?\" [W1].",
            ),
        )
    }

    @Test
    fun `a correction SelfCorrection takes out leaves nothing to find`() {
        val reply =
            "It came out in 2022 [MB1]. It sets the album's tone.\n\n" +
                "Wait, that second sentence is not supported, so here is the corrected insight:\n\n" +
                "It came out in 2022 [MB1]."
        assertEquals("It came out in 2022 [MB1].", shown(reply))
        assertNull(sign(reply))
        // As written, before SelfCorrection, it is found.
        assertEquals(SelfRevision.ASIDE, SelfRevision.of(reply))
    }

    @Test
    fun `clean replies, titles and lyrics that open with Wait, and stated gaps, are not found`() {
        val replies =
            listOf(
                "\"Wait\" first appeared on Rubber Soul [MB1]. It was written by Lennon and McCartney [MB2].",
                "\"Wait, Wait\" is the album's second single [MB1]. It charted in 1999 [W1].",
                "“Wait, I need to correct that” is the opening line of the chorus [W1]. It came out in 1965 [MB1].",
                "*Wait, Let Me Fix It* is the album's sixth track [MB1]. It was a hit [W1].",
                "The chorus runs \"wait, let me fix you, I'll drop the act\" [W1]. It came out in 1965 [MB1].",
                "It was recorded in 1965 [MB1].\nWait is the album's sixth track [MB2].",
                "It opens the set [MB1]. It quotes Monk's Evidence [W1]. I Will Survive followed it [W1].",
                "It was recorded in 1965 [MB1]. Actually, the band cut it in one take [W1].",
                // A stated gap: asked against, but prose, and no revision.
                "It was first released on 20 November 2023 [MB1]. The evidence doesn't say more about this " +
                    "recording, so that's the only detail I can share.",
                "It came out in 1965 [MB1]. That is all the evidence supports, so the card stays brief.",
                // Two paragraphs that open alike, but go on to say different things.
                "Gordon Lightfoot wrote it in 1973 [MB1].\n\nGordon Lightfoot recorded it with Red Shea in 1974 [W1].",
                "It came out in 1965... and it was a hit [MB1].",
                // Titles in emphasis that are an aside's word, but no header.
                "*Correction* is the album's opener [MB1]. *Wait* followed it [MB2].",
                "*Update* was the band's second single [MB1]. It charted in 1999 [W1].",
                // Two facts in sentences that share most of their words (four fifths here).
                "The studio version was recorded at Abbey Road in 1965 [MB1]. " +
                    "The live version was recorded at Abbey Road in 1966 [MB2].",
                "The studio version was recorded at Abbey Road [MB1]. " +
                    "The live version was recorded at Abbey Road [MB1].",
                "",
            )
        for (reply in replies) assertNull(SelfRevision.of(reply), reply)
    }

    // What the card shows of [reply] once a revision is cut out ([SelfRevision.salvage]); null when
    // nothing usable is left.
    private fun salvaged(reply: String): String? {
        val grounded = assertIs<InsightReply.Written>(GroundingCheck.check(reply, IDS, allowModel = false)).grounded
        val found = assertNotNull(SelfRevision.find(grounded.text), reply)
        return SelfRevision.salvage(grounded, found, IDS)?.text
    }

    @Test
    fun `a revision is cut to what follows it, when that is usable`() {
        // The phone's: the draft and the comment go, the last paragraph stays.
        assertEquals(
            "Basia Bulat's \"Already Forgiven\" was first released as a single in February 2020 [MB1]. " +
                "It appears on the album \"Are You in Love?\" [S3][S4].",
            salvaged(PHONE),
        )
        // A header: only the header goes with the draft, not the sentence it opens.
        val corrected = "It came out in 1975 [MB1]."
        assertEquals(corrected, salvaged("It came out in 1974 [MB1].\n\n**Correction:** $corrected"))
        assertEquals(corrected, salvaged("It came out in 1974 [MB1]. *Wait,* $corrected"))
        // Markdown, introductions and odd whitespace hide nothing: each is cut through its label.
        for (reply in HIDDEN) assertEquals(corrected, salvaged(reply), reply)
        // A repeated opening: kept from its second occurrence.
        assertEquals(
            "It came out on the album Sundown in 1974 [MB1]. It topped the charts [W1].",
            salvaged(
                "It came out on the album Sundown in 1974 [MB1]. It is a classic.\n\n" +
                    "It came out on the album Sundown in 1974 [MB1]. It topped the charts [W1].",
            ),
        )
        // The withdrawn part is kept for the developer.
        val grounded = assertIs<InsightReply.Written>(GroundingCheck.check(PHONE, IDS, allowModel = false)).grounded
        val cut = assertNotNull(SelfRevision.salvage(grounded, assertNotNull(SelfRevision.find(grounded.text)), IDS))
        assertTrue(cut.withdrawn!!.endsWith("so I'll keep only what is supported."), cut.withdrawn)
        assertEquals(0, cut.counts.uncited)
    }

    @Test
    fun `nothing usable after a revision is no salvage`() {
        val replies =
            listOf(
                // The correction ends the reply.
                "It came out in 1974 [MB1].\n\nWait, I need to correct that [MB1].",
                "It came out in 1974 [MB1]. **Correction:**",
                // What follows is uncited, cites a line that is not there, or revises itself again.
                "It came out in 1974 [MB1].\n\nWait, that is wrong [MB1]. It is a classic.",
                "It came out in 1974 [MB1].\n\nWait, that is wrong [MB1]. It came out in 1975 [X9].",
                "It came out in 1974 [MB1].\n\nWait, that is wrong [MB1]. It came out in 1975 [MB1]. Hmm, maybe.",
            )
        for (reply in replies) assertNull(salvaged(reply), reply)
    }

    /** No stored reply of the grounding check's (`grounding/replies.json`) revises itself. */
    @Test
    fun `no stored reply is found`() {
        val json = Json { ignoreUnknownKeys = true }
        val replies = json.decodeFromString<List<Stored>>(Fixtures.text("grounding/replies.json"))
        assertTrue(replies.isNotEmpty())
        for (reply in replies) assertNull(SelfRevision.of(reply.reply), reply.name)
    }

    @Serializable
    private data class Stored(
        val name: String,
        val reply: String,
    )

    internal companion object {
        val IDS = setOf("MB1", "MB2", "W1", "P1", "S3", "S4")

        /**
         * Corrections that formatting, an introduction or an unusual space once hid, each to be cut to
         * `It came out in 1975 [MB1].`: a list's mark before a header, a Markdown heading, an
         * introduction after an aside or in emphasis, a zero-width no-break space before an aside.
         */
        val HIDDEN =
            listOf(
                "It came out in 1974 [MB1].\n\n- **Correction:** It came out in 1975 [MB1].",
                "It came out in 1974 [MB1].\n\n### Correction:\nIt came out in 1975 [MB1].",
                "It came out in 1974 [MB1]. Wait, that is wrong [MB1]. Here is the corrected insight: " +
                    "It came out in 1975 [MB1].",
                "It came out in 1974 [MB1].\n\n**Corrected insight:** It came out in 1975 [MB1].",
                "It came out in 1974 [MB1].\n\n\uFEFFWait, that is wrong [MB1].\n\nIt came out in 1975 [MB1].",
            )

        // The phone's reply (#798, Basia Bulat, "Already Forgiven"), the parts the issue elides filled
        // in from what it quotes.
        const val PHONE =
            "Basia Bulat's \"Already Forgiven\" was first released as a single in February 2020 [MB1]. " +
                "It is the title track of nothing in particular here; it sits on the album " +
                "\"Are You in Love?\" [P1].\n\n" +
                "Wait, I need to correct that: the evidence says the album is \"Are You in Love?\" and that " +
                "the track is listed on it [S3][S4], so I'll keep only what is supported.\n\n" +
                "Basia Bulat's \"Already Forgiven\" was first released as a single in February 2020 [MB1]. " +
                "It appears on the album \"Are You in Love?\" [S3][S4]."
    }
}
