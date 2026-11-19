package app.sleevesight.app.ui.home

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.sources.WebPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/** The passages of the web pages *Sources* lists (#1434): repeats left out, a mid-sentence start trimmed. */
class SourcesPassagesTest {
    private val at = Instant.fromEpochMilliseconds(1)

    private val bio =
        "Damien Jurado is a singer-songwriter from Seattle. He released his first album in 1997, " +
            "earning high critical praise for its spare, intimate sound. His later records turned to psychedelia."

    // iHeart's page, AllMusic's biography again, as Exa's highlight gave it: from mid-sentence.
    private val iheart =
        WebPage(
            "https://www.iheart.com/artist/damien-jurado",
            null,
            ", earning high critical praise for its spare, intimate sound. His later records turned to psychedelia.",
        )
    private val allmusic = WebPage("https://www.allmusic.com/artist/damien-jurado", null, bio)
    private val other =
        WebPage("https://example.org/turn", null, "Turn is a folk song with a slow, hushed arrangement.")

    private fun lines(vararg texts: String) =
        SourcesLines(
            EvidenceSet.of(
                texts.mapIndexed { index, text ->
                    EvidenceDraft(SourceId.WEB, Scope.WEB, text, "https://example.net/$index", at)
                },
                JvmHasher,
            ),
        )

    // The lines after the first, the pages added.
    private fun shown(listed: SourcesLines) = listed.evidence!!.lines.drop(1)

    @Test
    fun aPageWhosePassageALongerPageOfTheSameListHoldsIsLeftOutWhicheverComesFirst() {
        val listed = withPages(lines("Own page."), listOf(iheart, allmusic, other), LinePurpose.GENRES, passages = true)
        assertEquals(listOf(allmusic.url, other.url), shown(listed).map { it.url })
        assertEquals(listOf("S2", "S3"), listed.purposes.keys.sorted())
        // Its citations go to the page that holds its passage, AllMusic's, S2.
        assertEquals(mapOf(iheart.url to "S2"), listed.moved)
    }

    @Test
    fun aPageWhosePassageALineAlreadyListedHoldsIsLeftOutAndCitedAsThatLine() {
        val listed = withPages(lines(bio), listOf(iheart, other), LinePurpose.VOCALS_CHECK, passages = true)
        assertEquals(listOf(other.url), shown(listed).map { it.url })
        assertEquals("S1", webLineOf(iheart.url, listed.evidence, listed.moved))
        // Kept from one call to the next: the explanation's pages after the check's.
        val after = withPages(listed, listOf(WebPage("https://example.org/x", null, "X")), LinePurpose.EXPLANATION)
        assertEquals("S1", webLineOf(iheart.url, after.evidence, after.moved))
    }

    @Test
    fun aPageLeftOutWithNoOtherPageIsCitedAsTheLineThatHoldsIt() {
        // Nothing fresh to list, only the repeat: still where its citations go.
        val listed = withPages(lines(bio), listOf(iheart), LinePurpose.GENRES, passages = true)
        assertEquals(1, listed.evidence!!.lines.size)
        assertEquals("S1", webLineOf(iheart.url, listed.evidence, listed.moved))
    }

    @Test
    fun aPageNearlyTheSameAsALineIsKept() {
        val again = WebPage("https://example.com/bio", null, bio.replace("Seattle.", "Seattle, Washington;"))
        val listed = withPages(lines(bio), listOf(again), LinePurpose.GENRES, passages = true)
        assertEquals(listOf(again.url), shown(listed).map { it.url })
    }

    @Test
    fun aPageThatSaysTheOppositeIsKept() {
        val says =
            "This recording is an instrumental track with a slow rhythm, lush strings and a quiet orchestral " +
                "arrangement throughout."
        val denies = WebPage("https://example.com/not", null, says.replace(" is an ", " is not an "))
        val listed = withPages(lines(says), listOf(denies), LinePurpose.GENRES, passages = true)
        assertEquals(listOf(denies.url), shown(listed).map { it.url })
        val pages = listOf(WebPage("https://a.org", null, says), denies)
        val both = withPages(lines("Own page."), pages, LinePurpose.GENRES, passages = true)
        assertEquals(2, shown(both).size)
    }

    @Test
    fun aPageWithAnotherNumberIsKept() {
        val year = WebPage("https://example.com/year", null, "He released his first album in 1998,")
        val listed = withPages(lines(bio), listOf(year), LinePurpose.GENRES, passages = true)
        assertEquals(listOf(year.url), shown(listed).map { it.url })
        assertFalse(repeats("4.5 stars", "Rated 45 stars."))
    }

    @Test
    fun pagesThatAreNotPassagesAreListedAsTheyAre() {
        // The reasons' pages of *Ask AI*, named by their site alone: two of one site are two pages.
        val one = WebPage("https://example.org/a", null, "example.org")
        val two = WebPage("https://example.org/b", null, "example.org")
        val listed = withPages(lines("Own page."), listOf(one, two), LinePurpose.GENRES)
        assertEquals(listOf("example.org", "example.org"), shown(listed).map { it.text })
    }

    @Test
    fun aPassageThatStartsMidSentenceKeepsEveryWordAfterAnEllipsis() {
        val listed = withPages(lines("Own page."), listOf(iheart), LinePurpose.GENRES, passages = true)
        assertEquals(
            "… earning high critical praise for its spare, intimate sound. His later records turned to psychedelia.",
            shown(listed).last().text,
        )
        // What a citation of it rests on stays: the fragment before the next sentence.
        assertEquals(
            "… an instrumental track with no vocals. It was released in 1997.",
            shownPassage(", an instrumental track with no vocals. It was released in 1997."),
        )
        // A negation before a capital letter stays.
        assertEquals("… and not Instrumental.", shownPassage("and not Instrumental."))
        assertEquals("… and moved to Seattle, his home.", shownPassage("and moved to Seattle, his home."))
        assertEquals("… all in small letters", shownPassage("all in small letters"))
        // The marks before its first word go, an opening quote stays.
        assertEquals("… “Turn” is a song.", shownPassage("; “Turn” is a song."))
        assertEquals("… and then", shownPassage("... and then"))
    }

    @Test
    fun aPassageThatStartsASentenceIsShownAsItIs() {
        assertEquals("He released his first album.", shownPassage("  He released his first album. "))
        assertEquals("“Turn” is a song.", shownPassage("“Turn” is a song."))
        assertEquals("… Already trimmed.", shownPassage("… Already trimmed."))
        assertEquals("1997 saw his first album.", shownPassage("1997 saw his first album."))
    }

    @Test
    fun aPassageRepeatsALineOnlyWhole() {
        assertTrue(repeats("Seattle", "From Seattle, Washington."))
        assertTrue(repeats(", from SEATTLE  washington", "From Seattle, Washington."))
        assertFalse(repeats("Seattle band", "From Seattle, Washington."))
        assertFalse(repeats("", "Anything."))
        assertFalse(repeats("Page 1.", "Page 2."))
        // A word's part is not the word.
        assertFalse(repeats("attle", "From Seattle."))
    }

    @Test
    fun aCitationOfALineSourcesHasNoLineForShowsNothing() {
        val reasons = lines("Own page.", "Another page.")
        // The reason's S2, a page *Sources* lists no line for; *Sources*' S2 is another page.
        val sources =
            EvidenceSet.of(
                listOf("Own page.", "Unrelated.").mapIndexed { index, text ->
                    EvidenceDraft(SourceId.WEB, Scope.WEB, text, "https://example.com/$index", at)
                },
                JvmHasher,
            )
        assertEquals(null, shownLine("S2", reasons.evidence, sources))
        assertEquals("S2", shownLine("S2", reasons.evidence, sources, mapOf("https://example.net/1" to "S2")))
    }
}
