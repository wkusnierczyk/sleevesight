package app.sleevesight.core.synthesis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The song's part headings (#1133, [SongParts]), as the grounding check reads them. */
class SongPartsTest {
    @Test
    fun `a line that is a heading, perhaps marked up, is read as it`() {
        assertEquals(SongParts.MUSIC, SongParts.heading("The music"))
        assertEquals(SongParts.MUSIC, SongParts.heading("## The Music:"))
        assertEquals(SongParts.RECORDING, SongParts.heading("**This recording**"))
        assertNull(SongParts.heading("The music was composed in 1788 [W1]."))
        assertNull(SongParts.heading("Music"))
    }

    @Test
    fun `with headings, each is a paragraph of its own, uncited on purpose and counted in none`() {
        val reply = "## The music\nComposed in 1788 [W1].\n\n**This recording:**\nRecorded in 2024 [P1]."
        val checked = GroundingCheck.check(reply, setOf("W1", "P1"), false, headings = true)
        val grounded = (checked as InsightReply.Written).grounded
        assertEquals(
            listOf(SongParts.MUSIC, "Composed in 1788 [W1].", SongParts.RECORDING, "Recorded in 2024 [P1]."),
            grounded.paragraphs.map { paragraph -> paragraph.joinToString(" ") { it.text } },
        )
        val headings = grounded.paragraphs.flatten().filter { it.heading }
        assertEquals(2, headings.size)
        assertTrue(headings.all { it.status == SentenceStatus.HEDGE && it.cites.isEmpty() })
        assertEquals(GroundingCounts(ok = 2, model = 0, hedge = 0, uncited = 0, unknown = 0), grounded.counts)
    }

    @Test
    fun `without headings, a heading line is read as before`() {
        val reply = "The music\n\nComposed in 1788 [W1]."
        val grounded = (GroundingCheck.check(reply, setOf("W1"), false) as InsightReply.Written).grounded
        assertTrue(grounded.paragraphs.flatten().none { it.heading })
        assertEquals(1, grounded.counts.uncited)
    }
}
