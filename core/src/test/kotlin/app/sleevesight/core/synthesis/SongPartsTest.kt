package app.sleevesight.core.synthesis

import app.sleevesight.core.text.trimJs
import org.junit.jupiter.api.Timeout
import kotlin.random.Random
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
        // The decorations in any order: the colon inside the emphasis or after it, `__` or `#`s.
        for (line in listOf("**The music:**", "**The music**:", "__The music__:", "# **The music**", "## The music")) {
            assertEquals(SongParts.MUSIC, SongParts.heading(line), line)
        }
        for (line in listOf("The music.", "The music —", "The music –", "**The music** -", "The music:  ")) {
            assertEquals(SongParts.MUSIC, SongParts.heading(line), line)
        }
        for (line in listOf("__This recording:__", "### This recording:", "*This recording*:")) {
            assertEquals(SongParts.RECORDING, SongParts.heading(line), line)
        }
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
    fun `a heading with its colon after the emphasis is a heading, not a dimmed sentence`() {
        val reply = "**The music**:\nComposed in 1788 [W1]."
        val grounded =
            (GroundingCheck.check(reply, setOf("W1"), false, headings = true) as InsightReply.Written).grounded
        assertEquals(
            SongParts.MUSIC,
            grounded.paragraphs
                .first()
                .single()
                .text,
        )
        assertTrue(
            grounded.paragraphs
                .first()
                .single()
                .heading,
        )
        assertEquals(0, grounded.counts.uncited)
    }

    @Test
    fun `a heading with nothing of its part after it is left out, the model's or what a note left`() {
        val ids = setOf("W1")

        fun headings(reply: String) =
            (GroundingCheck.check(reply, ids, false, notes = true, headings = true) as InsightReply.Written)
                .grounded
                .paragraphs
                .flatten()
                .filter { it.heading }
                .map { it.text }
        // At the end, and before the next heading.
        assertEquals(listOf(SongParts.MUSIC), headings("The music\n\nOne [W1].\n\nThis recording"))
        assertEquals(listOf(SongParts.RECORDING), headings("The music\n\nThis recording\n\nTwo [W1]."))
        assertEquals(
            "${SongParts.MUSIC}\n\nOne [W1].",
            SongParts.separated("The music\nOne [W1].\n\n**This recording:**"),
        )
        assertEquals(listOf(0, 1, 2, 3), SongParts.withoutBare(listOf(0, 1, 2, 3, 4)) { it % 2 == 0 })
        assertEquals(listOf(2, 1, 2, 3), SongParts.withoutBare(listOf(0, 2, 1, 2, 3)) { it % 2 == 0 })
    }

    @Test
    fun `without headings, a heading line is read as before`() {
        val reply = "The music\n\nComposed in 1788 [W1]."
        val grounded = (GroundingCheck.check(reply, setOf("W1"), false) as InsightReply.Written).grounded
        assertTrue(grounded.paragraphs.flatten().none { it.heading })
        assertEquals(1, grounded.counts.uncited)
    }

    @Test
    fun `a line is read as a heading as the pass-by-pass trim before did, over varied lines`() {
        // The trim before #1291 took one mark off each end a pass at a time, a copy of the line for each.
        fun before(line: String): String? {
            var bare = line.trimJs()
            while (true) {
                val next = bare.trimStart('#', '*', '_').trimEnd { it in "*_:.\u2014\u2013-" }.trimJs()
                if (next == bare) break
                bare = next
            }
            return SongParts.HEADINGS.firstOrNull { it.equals(bare, ignoreCase = true) }
        }

        val random = Random(SEED)

        fun marks(): String = String(CharArray(random.nextInt(MAX_MARKS + 1)) { MARKS[random.nextInt(MARKS.length)] })
        repeat(LINES) {
            val middle = listOf("The music", "THIS RECORDING", "music", "#", "The music: on", "").random(random)
            val line = marks() + middle + marks()
            assertEquals(before(line), SongParts.heading(line), line)
        }
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a long line of marks is read in linear time`() {
        // 2M characters of `# `, alone, before a heading, or of ` -` after one: the trim before took time
        // growing with the square of the length, about a minute and a half at this one on a laptop.
        val marks = "# ".repeat(LONG)
        assertNull(SongParts.heading(marks))
        assertEquals(SongParts.MUSIC, SongParts.heading(marks + SongParts.MUSIC))
        assertEquals(SongParts.RECORDING, SongParts.heading(SongParts.RECORDING + " -".repeat(LONG)))
    }

    private companion object {
        /** What may come around a heading, and what may not: marks, spaces, line breaks, letters. */
        const val MARKS = "##**__::..\u2014\u2013-- \t\n\u00A0a,"
        const val MAX_MARKS = 6
        const val SEED = 1291
        const val LINES = 20_000

        /** A million `# ` or ` -`: 2M characters. */
        const val LONG = 1_000_000
    }
}
