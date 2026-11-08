package app.sleevesight.app.ui.home

import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import org.junit.Assert.assertEquals
import org.junit.Test

/** A citation after a sentence's stop shows before it, as the others are (#1061). */
class CitesBeforeStopsTest {
    // A text's sentences as shown, each its text with its citations in brackets.
    private fun plain(sentences: List<String>): List<String> =
        shown(sentences).map { runs ->
            runs.joinToString("") {
                when (it) {
                    is ShownRun.Text -> it.text
                    is ShownRun.Cite -> "[${it.id}]"
                }
            }
        }

    // A paragraph not checked yet, shown whole, as a streaming one is.
    private fun unchecked(paragraph: String): String = plain(listOf(paragraph)).single()

    // A reply's paragraphs as the grounding check splits them, each its sentences as shown.
    private fun checked(reply: String): List<List<String>> {
        val ids = setOf("S1", "S2", "S15", "S16", "W1", "MB3")
        val grounded = (GroundingCheck.check(reply, ids, allowModel = false) as InsightReply.Written).grounded
        return grounded.paragraphs.map { paragraph -> plain(paragraph.map { it.text }) }
    }

    @Test
    fun aCitationAfterTheLastStopShowsBeforeIt() {
        val reply = "It explores the darker and obsessive side of love [S15]. [S16]"
        assertEquals(listOf(listOf("It explores the darker and obsessive side of love [S15] [S16].")), checked(reply))
        assertEquals(
            listOf(
                ShownRun.Text("It explores love "),
                ShownRun.Cite("S15"),
                ShownRun.Text(" "),
                ShownRun.Cite("S16"),
                ShownRun.Text("."),
            ),
            shown(listOf("It explores love [S15]. [S16]")).single(),
        )
    }

    @Test
    fun severalCitationsAfterTheStopAllShowBeforeIt() {
        assertEquals("It charted [W1] [S2] [MB3].", unchecked("It charted [W1]. [S2] [MB3]"))
        assertEquals("It charted [S2][MB3].", unchecked("It charted. [S2][MB3]"))
        // Spaced as written.
        assertEquals("It charted[S2].", unchecked("It charted.[S2]"))
    }

    @Test
    fun anExclamationOrQuestionMarkTooAndClosingBracketsWithIt() {
        assertEquals("Who knew [S1]?", unchecked("Who knew? [S1]"))
        assertEquals("What a hit [S1]!", unchecked("What a hit! [S1]"))
        assertEquals("Really [S1]?!", unchecked("Really?! [S1]"))
        assertEquals("(See the sleeve [W1].)", unchecked("(See the sleeve.) [W1]"))
    }

    @Test
    fun theNextSentenceALineBreakOrAParagraphBreakMayFollow() {
        // A paragraph not checked yet holds its sentences in one text.
        assertEquals("It is about love [S1]. It charted [W1].", unchecked("It is about love. [S1] It charted [W1]."))
        assertEquals("It is about love [S1].\nit charted [W1].", unchecked("It is about love. [S1]\nit charted [W1]."))
        assertEquals(
            "A [S1]. B [S2]. C [W1].",
            unchecked("A. [S1] B. [S2] C. [W1]"),
        )
        // Checked, the citation is the sentence's before it, and shows there.
        assertEquals(
            listOf(listOf("It is about love [S1].", "It charted [W1].")),
            checked("It is about love. [S1] It charted [W1]."),
        )
        assertEquals(
            listOf(listOf("It is about love [S1].", "It charted [W1].")),
            checked("It is about love. [S1]\nIt charted [W1]."),
        )
        assertEquals(
            listOf(listOf("It is about love [S1]."), listOf("It charted [W1].")),
            checked("It is about love. [S1]\n\nIt charted [W1]."),
        )
    }

    @Test
    fun aCitationWithinASentenceStaysWhereItIs() {
        for (sentence in listOf(
            "It charted [W1] and sold well [S2].",
            "It covers Help! [W1].",
            "It charted. [W1], then sold.",
        )) {
            assertEquals(sentence, unchecked(sentence))
        }
        // A citation the check opens the next sentence with, a list item's, stays with that sentence.
        assertEquals(
            listOf("It is great [W1].", "[S1] It charted."),
            plain(GroundingCheck.splitSentences("It is great [W1].\n- [S1] It charted.")),
        )
    }

    @Test
    fun aStopThatMayBeATitlesOwnOrInitialsStaysAsWritten() {
        for (sentence in listOf(
            "They called it \"Help!\" [W1]",
            "It is the album *DAMN.* [W1]",
            "It charted in the U.S. [W1]",
        )) {
            assertEquals(sentence.replace("*", ""), unchecked(sentence))
        }
        // Initials before a closing bracket too, checked and streaming.
        assertEquals("It charted (in the U.S.) [W1]", unchecked("It charted (in the U.S.) [W1]"))
        assertEquals(
            "It charted (in the U.S.) [W1] It sold.",
            unchecked("It charted (in the U.S.) [W1] It sold."),
        )
        assertEquals(
            listOf(listOf("It sold [S1].", "It charted (in the U.S.) [W1]")),
            checked("It sold [S1]. It charted (in the U.S.) [W1]"),
        )
        // The check reads no stop before a `)` as a sentence's end, so this is one sentence.
        assertEquals(
            listOf(listOf("It charted (in the U.S.) [W1] It sold [S1].")),
            checked("It charted (in the U.S.) [W1] It sold [S1]."),
        )
        // A stop after the emphasis moves.
        assertEquals("It is the album Sundown [W1].", unchecked("It is the album *Sundown*. [W1]"))
    }

    @Test
    fun aTextWithoutCitationsStaysAsWritten() {
        for (text in listOf("It is great. It charted! Did it?", "It is great.\nIt charted.", "Fine. ")) {
            assertEquals(text, unchecked(text))
        }
    }
}
