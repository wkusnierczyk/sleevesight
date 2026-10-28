package app.sleevesight.app.ui.home

import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.SentenceStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Markdown a model writes anyway, cleaned for display (#555), its citations as they were. */
class ShownRunTest {
    private fun text(
        text: String,
        italic: Boolean = false,
        bold: Boolean = false,
    ) = ShownRun.Text(text, italic, bold)

    private fun cite(id: String) = ShownRun.Cite(id)

    // One sentence, as shown.
    private fun one(sentence: String): List<ShownRun> = shown(listOf(sentence)).single()

    // A sentence's text as shown, its marks gone.
    private fun plain(sentence: String): String =
        one(sentence).joinToString("") {
            when (it) {
                is ShownRun.Text -> it.text
                is ShownRun.Cite -> "[${it.id}]"
            }
        }

    @Test
    fun aTitleInAsterisksShowsInItalics() =
        assertEquals(
            listOf(
                text("It quotes "),
                text("Project Hail Mary", italic = true),
                text(" and "),
                text("Crazy World", italic = true),
                text("."),
            ),
            one("It quotes *Project Hail Mary* and *Crazy World*."),
        )

    @Test
    fun aTitleInUnderscoresShowsInItalics() =
        assertEquals(listOf(text("Title", italic = true), text(" charted.")), one("_Title_ charted."))

    @Test
    fun doubleAsterisksAndUnderscoresShowInBold() {
        assertEquals(listOf(text("It was "), text("huge", bold = true), text(".")), one("It was **huge**."))
        assertEquals(listOf(text("It was "), text("huge", bold = true), text(".")), one("It was __huge__."))
    }

    @Test
    fun threeAsterisksShowInBoldItalics() =
        assertEquals(
            listOf(text("It was "), text("huge", italic = true, bold = true), text(".")),
            one("It was ***huge***."),
        )

    @Test
    fun aCitationAfterATitleInItalicsStaysACitation() =
        assertEquals(
            listOf(text("It covers "), text("Title", italic = true), text(" "), cite("MB1"), text(".")),
            one("It covers *Title* [MB1]."),
        )

    @Test
    fun aCitationInsideItalicsStaysACitationAndTheMarksPairAroundIt() =
        assertEquals(
            listOf(text("It covers "), text("Title ", italic = true), cite("MB1"), text(".")),
            one("It covers *Title [MB1]*."),
        )

    @Test
    fun aHeadingsMarkIsTakenOut() {
        assertEquals("Sundown\nIt charted [W1].", plain("# Sundown\nIt charted [W1]."))
        assertEquals("Sundown\nIt charted [W1].", plain("### Sundown\nIt charted [W1]."))
        // A `#` that is no heading stays: a number, a key.
        assertEquals("It reached #1 in C# minor [W1].", plain("It reached #1 in C# minor [W1]."))
        assertEquals("#1 for weeks [W1].", plain("#1 for weeks [W1]."))
    }

    @Test
    fun aListsMarkersAreTakenOut() {
        assertEquals("It charted [W1].\nIt sold [MB1].", plain("- It charted [W1].\n- It sold [MB1]."))
        assertEquals("It charted [W1].\nIt sold [MB1].", plain("* It charted [W1].\n* It sold [MB1]."))
        assertEquals("It charted [W1].\nIt sold [MB1].", plain("+ It charted [W1].\n  + It sold [MB1]."))
        assertEquals("It charted [W1].\nIt sold [MB1].", plain("1. It charted [W1].\n2) It sold [MB1]."))
        // A marker only at a line's start: a dash inside a line, and a year, stay.
        assertEquals("It charted - twice [W1].", plain("It charted - twice [W1]."))
        assertEquals("1973. It charted [W1].", plain("1973. It charted [W1]."))
    }

    @Test
    fun aQuotationsMarkAndARuleAreTakenOut() {
        assertEquals("They called it great [W1].", plain("> They called it great [W1]."))
        assertEquals("It charted [W1].\nIt sold.", plain("It charted [W1].\n---\nIt sold."))
        assertEquals("It charted [W1].\nIt sold.", plain("It charted [W1].\n* * *\nIt sold."))
        // A rule that ends the paragraph takes the line break before it: no empty line is left.
        assertEquals("It charted [W1].", plain("It charted [W1].\n---"))
        assertEquals(listOf<ShownRun>(), one("---"))
    }

    @Test
    fun aMarkAfterABackslashIsShownAsWrittenWithoutTheBackslash() {
        assertEquals(
            listOf(text("It was "), text("Star*Title", bold = true), text(" "), cite("W1"), text(".")),
            one("It was **Star\\*Title** [W1]."),
        )
        assertEquals(listOf(text("It is *not italic* "), cite("W1"), text(".")), one("It is \\*not italic\\* [W1]."))
        assertEquals("It is _raw_ and a \\ [W1].", plain("It is \\_raw\\_ and a \\\\ [W1]."))
        assertEquals("[not a link](https://x.org) [W1].", plain("\\[not a link](https://x.org) [W1]."))
        assertEquals("# no heading [W1].", plain("\\# no heading [W1]."))
        // A backslash before a letter stays; inside code, both show.
        assertEquals("C:\\Music [W1].", plain("C:\\Music [W1]."))
        assertEquals("It is a\\*b [W1].", plain("It is `a\\*b` [W1]."))
    }

    @Test
    fun codeShowsItsBackslashesAndAnEscapedBacktickOpensNoCode() {
        assertEquals("Its paths are C:\\ and D:\\ [W1].", plain("Its paths are `C:\\` and `D:\\` [W1]."))
        assertEquals("It is a\\b` [W1].", plain("It is `a\\`b` [W1]."))
        assertEquals("It is `not code` [W1].", plain("It is \\`not code` [W1]."))
        // One space each side of code goes, so it can hold a backtick at its ends.
        assertEquals("Use `x` here [W1].", plain("Use `` `x` `` here [W1]."))
        assertEquals("Use  x  here [W1].", plain("Use `  x  ` here [W1]."))
        assertEquals("Use   here [W1].", plain("Use ` ` here [W1]."))
    }

    @Test
    fun aBackslashBeforeACitationIsTakenOutAndTheCitationKept() =
        assertEquals(listOf(text("It charted "), cite("W1"), text(".")), one("It charted \\[W1]."))

    @Test
    fun aWebAddressWrittenOutIsShownAsWritten() {
        val two =
            "It is https://en.wikipedia.org/wiki/Help!_(song) and https://en.wikipedia.org/wiki/Help!_(album) [W1]."
        assertEquals(listOf(text(two.removeSuffix(" [W1].") + " "), cite("W1"), text(".")), one(two))
        assertEquals(
            listOf(
                text("See https://x.org/a*b*c for "),
                text("Title", italic = true),
                text(" "),
                cite("W1"),
                text("."),
            ),
            one("See https://x.org/a*b*c for *Title* [W1]."),
        )
        assertEquals(
            listOf(
                text("It charted "),
                cite("W1"),
                text(" https://x.org/a_b_c and "),
                text("Title", italic = true),
                text("."),
            ),
            one("It charted [W1] https://x.org/a_b_c and _Title_."),
        )
        // An address whose `_`s touch punctuation, which would pair as italics.
        assertEquals(
            listOf(
                text("It charted "),
                cite("W1"),
                text("www.x.org/(a)_b_(c) and "),
                text("Title", italic = true),
                text("."),
            ),
            one("It charted [W1]www.x.org/(a)_b_(c) and _Title_."),
        )
        // An address as a link's text ends where the link's marks were taken out.
        assertEquals(
            listOf(text("See "), text("www.x.org/a_b", italic = true), text(" "), cite("W1"), text(".")),
            one("See [*www.x.org/a_b*](https://x.org) [W1]."),
        )
        // The punctuation that ends an address is not part of it: here, the closing mark of italics.
        assertEquals(
            listOf(text("see www.x.org/a_b", italic = true), text(" "), cite("W1")),
            one("*see www.x.org/a_b* [W1]"),
        )
    }

    @Test
    fun aRunInsideAWordStays() {
        for (sentence in listOf(
            "M*A*S*H aired it [W1].",
            "N*E*R*D covered it [W1].",
            "F**k You by CeeLo, then F**k You again [W1].",
            "Sh*t and B*tch are its titles [W1].",
        )) {
            assertEquals(sentence, plain(sentence))
            assertEquals(
                listOf(false),
                one(sentence).filterIsInstance<ShownRun.Text>().map { it.italic || it.bold }.distinct(),
            )
        }
    }

    @Test
    fun aLinksAddressMayHoldBracketsAndMustReadAsAnAddress() {
        assertEquals(
            "It covers Help! [W1].",
            plain("It covers [Help!](https://en.wikipedia.org/wiki/Help!_(song)) [W1]."),
        )
        assertEquals("It covers Help! [W1].", plain("It covers [Help!](https://x.org/Help!_\\(song) [W1]."))
        assertEquals("It covers Help! [W1].", plain("It covers [Help!](en.wikipedia.org/wiki/Help!) [W1]."))
        // Not an address: a year, a word.
        assertEquals("It is the single [Live](1999) [W1].", plain("It is the single [Live](1999) [W1]."))
        assertEquals("It is the single [Live](encore) [W1].", plain("It is the single [Live](encore) [W1]."))
    }

    @Test
    fun codesBackticksAreTakenOutAndWhatIsInsideStaysAsWritten() {
        assertEquals("It is on side_a_b [W1].", plain("It is on `side_a_b` [W1]."))
        assertEquals(listOf(text("It is *not* a title.")), one("It is `*not*` a title."))
        // A backtick with no pair stays.
        assertEquals("It's `90s pop [W1].", plain("It's `90s pop [W1]."))
    }

    @Test
    fun aLinkShowsAsItsText() {
        assertEquals(
            "Its Wikipedia article says so [W1].",
            plain("Its [Wikipedia article](https://en.wikipedia.org/x) says so [W1]."),
        )
        assertEquals("A cover shows it [W1].", plain("A ![cover](https://x.org/c.jpg) shows it [W1]."))
        // Italics in a link's text stay italics.
        assertEquals(
            listOf(text("See "), text("Title", italic = true), text(" "), cite("W1"), text(".")),
            one("See [*Title*](https://x.org) [W1]."),
        )
    }

    @Test
    fun aCitationWrittenAsALinkStaysTheCitationWithoutTheAddress() {
        assertEquals("It charted [W1].", plain("It charted [W1](https://en.wikipedia.org/x)."))
        // Brackets that are no link stay, and so does a year after a citation.
        assertEquals("It charted [sic] [W1] (1973).", plain("It charted [sic] [W1] (1973)."))
        assertEquals("It charted [W1](1973).", plain("It charted [W1](1973)."))
    }

    @Test
    fun anAsteriskThatIsNoMarkdownStays() {
        assertEquals(
            listOf(text("It is 5 * 3 minutes long "), cite("W1"), text(".")),
            one("It is 5 * 3 minutes long [W1]."),
        )
        assertEquals(listOf(text("It is loved * "), cite("W1")), one("It is loved * [W1]"))
        assertEquals(listOf(text("*")), one("*"))
        assertEquals(listOf(text("Its B-side* charted "), cite("W1"), text(".")), one("Its B-side* charted [W1]."))
        assertEquals(listOf(text("It is **"), cite("W1")), one("It is **[W1]"))
    }

    @Test
    fun anUnderscoreInsideAWordStays() =
        assertEquals(
            listOf(text("Its file is snake_case_name "), cite("W1"), text(".")),
            one("Its file is snake_case_name [W1]."),
        )

    @Test
    fun anOpeningMarkThatNeverClosesStays() {
        assertEquals(listOf(text("*NSYNC recorded it "), cite("W1"), text(".")), one("*NSYNC recorded it [W1]."))
        assertEquals(
            listOf(text("*NSYNC recorded "), text("Celebrity", italic = true), text(" "), cite("W1"), text(".")),
            one("*NSYNC recorded *Celebrity* [W1]."),
        )
    }

    @Test
    fun plainTextIsShownAsItCame() {
        val sentence = "It topped the charts in the U.S. in 1973, \"Sundown\" (live) [W1][MB1]."
        assertEquals(
            listOf(
                text("It topped the charts in the U.S. in 1973, \"Sundown\" (live) "),
                cite("W1"),
                cite("MB1"),
                text("."),
            ),
            one(sentence),
        )
    }

    @Test
    fun aTitleTheCheckSplitsIsSetInItalicsAcrossItsSentences() {
        val sentences = GroundingCheck.splitSentences("*Help! I Need Somebody* opens it [W1].")
        assertEquals(listOf("*Help!", "I Need Somebody* opens it [W1]."), sentences)
        assertEquals(
            listOf(
                listOf(text("Help!", italic = true)),
                listOf(text("I Need Somebody", italic = true), text(" opens it "), cite("W1"), text(".")),
            ),
            shown(sentences),
        )
    }

    @Test
    fun aLinesMarkThatOpensASentenceTheCheckSplitOffIsTakenOut() {
        for (mark in listOf("-", "*", "+", ">", "1.", "2)", "##")) {
            val sentences = GroundingCheck.splitSentences("$mark It was recorded [MB1].\n$mark It is great.")
            assertEquals(2, sentences.size)
            assertEquals(
                listOf(listOf(text("It was recorded "), cite("MB1"), text(".")), listOf(text("It is great."))),
                shown(sentences),
            )
        }
        // A sentence opening with emphasis keeps it.
        assertEquals(
            listOf(
                listOf(text("It charted "), cite("W1"), text(".")),
                listOf(text("Sundown", italic = true), text(" is great.")),
            ),
            shown(GroundingCheck.splitSentences("It charted [W1]. *Sundown* is great.")),
        )
    }

    @Test
    fun theGroundingCheckReadsTheReplyAsWrittenAndTheShownTextKeepsItsSentencesAndCitations() {
        val reply = "It covers *Title* [MB1]. It was **huge**. _Other_ is cited [AI]."
        val grounded = (GroundingCheck.check(reply, setOf("MB1"), allowModel = false) as InsightReply.Written).grounded
        val paragraph = grounded.paragraphs.single()
        assertEquals(
            listOf("It covers *Title* [MB1].", "It was **huge**.", "_Other_ is cited [AI]."),
            paragraph.map { it.text },
        )
        // A sentence opening with emphasis is a sentence of its own, so the uncited one shows (#574).
        assertEquals(
            listOf(SentenceStatus.OK, SentenceStatus.UNCITED, SentenceStatus.UNKNOWN),
            paragraph.map { it.status },
        )
        val runs = shown(paragraph.map { it.text })
        assertEquals(paragraph.size, runs.size)
        assertEquals(
            paragraph.map { it.cites },
            runs.map { sentence ->
                sentence.filterIsInstance<ShownRun.Cite>().map { it.id }
            },
        )
        assertEquals(listOf(text("It was "), text("huge", bold = true), text(".")), runs[1])
        assertEquals(listOf(text("Other", italic = true), text(" is cited "), cite("AI"), text(".")), runs[2])
    }
}
