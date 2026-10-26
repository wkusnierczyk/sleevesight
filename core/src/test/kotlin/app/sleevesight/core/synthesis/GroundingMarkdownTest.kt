package app.sleevesight.core.synthesis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The grounding check's sentences in a reply written in Markdown, against the prompt's contract
 * (#555): a sentence may open with a mark, which is part of it, so an uncited one is not hidden by
 * the citation of the one before it (#574).
 */
class GroundingMarkdownTest {
    private fun statuses(reply: String): List<SentenceStatus> =
        assertIs<InsightReply.Written>(GroundingCheck.check(reply, setOf("MB1", "W1"), allowModel = false))
            .grounded.paragraphs
            .flatten()
            .map { it.status }

    @Test
    fun `an uncited list item after a cited one is a sentence of its own`() {
        for (bullet in listOf("-", "*", "+", ">")) {
            val reply = "$bullet It was recorded [MB1].\n$bullet It is great."
            assertEquals(
                listOf("$bullet It was recorded [MB1].", "$bullet It is great."),
                GroundingCheck.splitSentences(reply),
                bullet,
            )
            assertEquals(listOf(SentenceStatus.OK, SentenceStatus.UNCITED), statuses(reply), bullet)
        }
    }

    @Test
    fun `a numbered list's items are sentences, their numbers ending none`() {
        for ((one, two) in listOf("1." to "2.", "1)" to "2)", "9." to "10.")) {
            val reply = "$one It was recorded. [MB1]\n$two It is great."
            assertEquals(
                listOf("$one It was recorded. [MB1]", "$two It is great."),
                GroundingCheck.splitSentences(reply),
                one,
            )
            assertEquals(listOf(SentenceStatus.OK, SentenceStatus.UNCITED), statuses(reply), one)
        }
    }

    @Test
    fun `a heading opens a sentence after a stop, and is joined to the sentence after it`() {
        val reply = "# Sundown\nIt was recorded [MB1].\n## Reception\nIt is great."
        assertEquals(
            listOf("# Sundown\nIt was recorded [MB1].", "## Reception\nIt is great."),
            GroundingCheck.splitSentences(reply),
        )
        assertEquals(listOf(SentenceStatus.OK, SentenceStatus.UNCITED), statuses(reply))
    }

    @Test
    fun `an uncited sentence opening with an italic or bold title is a sentence of its own`() {
        val titles = listOf("*Sundown*", "_Sundown_", "**Sundown**", "***Sundown***", "*\"Sundown\"*", "*(Sundown)*")
        for (title in titles) {
            for (cited in listOf("It was recorded in 1973 [MB1].", "It was recorded in 1973. [MB1]")) {
                val reply = "$cited $title is great."
                assertEquals(listOf(cited, "$title is great."), GroundingCheck.splitSentences(reply), reply)
                assertEquals(listOf(SentenceStatus.OK, SentenceStatus.UNCITED), statuses(reply), reply)
            }
        }
    }

    @Test
    fun `a stop inside closing emphasis ends a sentence when citations follow, as inside closing quotes`() {
        for (end in listOf(".*", "!_", "?**", ".\"*", ".*\"")) {
            val reply = "It opens the album *Sundown$end [MB1] It is great."
            assertEquals(
                listOf("It opens the album *Sundown$end [MB1]", "It is great."),
                GroundingCheck.splitSentences(reply),
                end,
            )
        }
        // With no citation after the marks, a title's stop sits mid-sentence, as a quoted one does.
        val reply = "He called it *Help!* It charted. [W1]"
        assertEquals(listOf(reply), GroundingCheck.splitSentences(reply))
    }

    @Test
    fun `a mark mid-line, or a title mid-sentence, opens nothing`() {
        val replies =
            listOf(
                "It charted. [W1] - It is great.",
                "It charted. [W1] * It is great.",
                "It charted. [W1] # It is great.",
                "The album *Sundown* (1974) topped the chart. [W1]",
                "Lightfoot wrote it, and it came out on *Sundown* in 1974 [MB1].",
                "It was recorded [MB1].\nit is great, and *sundown* is too.",
                "Highlights:\n- It was recorded [MB1].",
                "Five * Three is a title. [W1]",
            )
        for (reply in replies) assertEquals(listOf(reply), GroundingCheck.splitSentences(reply), reply)
    }

    @Test
    fun `a list's number at the reply's start ends no sentence`() {
        val reply = "1. It was recorded [MB1]."
        assertEquals(listOf(reply), GroundingCheck.splitSentences(reply))
        assertEquals(listOf(SentenceStatus.OK), statuses(reply))
    }

    @Test
    fun `abbreviations read through emphasis marks, and a list item after one is a sentence`() {
        val kept =
            listOf(
                "Produced with Dr. *Dre* on vocals. [W1]",
                "It reached the U.S. *Billboard* Hot 100. [W1]",
                "It reached the U.S. **Billboard** Hot 100. [W1]",
            )
        for (reply in kept) assertEquals(listOf(reply), GroundingCheck.splitSentences(reply), reply)
        assertEquals(
            listOf("It was a hit in the U.S.", "*It* charted. [W1]"),
            GroundingCheck.splitSentences("It was a hit in the U.S. *It* charted. [W1]"),
        )
        assertEquals(
            listOf("It was a hit in the U.S.", "- Billboard named it. [W1]"),
            GroundingCheck.splitSentences("It was a hit in the U.S.\n- Billboard named it. [W1]"),
        )
        assertEquals(
            listOf("It was produced with Dr.", "- Dre on vocals. [W1]"),
            GroundingCheck.splitSentences("It was produced with Dr.\n- Dre on vocals. [W1]"),
        )
    }
}
