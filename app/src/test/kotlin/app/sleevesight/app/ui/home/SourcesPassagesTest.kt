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
    }

    @Test
    fun aPageWhosePassageALineAlreadyListedHoldsIsLeftOut() {
        val listed = withPages(lines(bio), listOf(iheart, other), LinePurpose.VOCALS_CHECK, passages = true)
        assertEquals(listOf(other.url), shown(listed).map { it.url })
    }

    @Test
    fun aPageWhosePassageIsNearlyTheSameAsALineIsLeftOut() {
        val again = WebPage("https://example.com/bio", null, bio.replace("Seattle.", "Seattle, Washington;"))
        val listed = withPages(lines(bio), listOf(again), LinePurpose.GENRES, passages = true)
        assertEquals(1, listed.evidence!!.lines.size)
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
    fun aPassageThatStartsMidSentenceShowsFromTheNextSentenceWithAnEllipsis() {
        val listed = withPages(lines("Own page."), listOf(iheart), LinePurpose.GENRES, passages = true)
        assertEquals("… His later records turned to psychedelia.", shown(listed).last().text)
        assertEquals("… Seattle is his home.", shownPassage("where he was born. Seattle is his home."))
        // No sentence starts in it: from its first capital letter.
        assertEquals("… Seattle, his home.", shownPassage("and moved to Seattle, his home."))
    }

    @Test
    fun aPassageThatStartsASentenceOrHasNoCapitalLetterIsShownAsItIs() {
        assertEquals("He released his first album.", shownPassage("  He released his first album. "))
        assertEquals("“Turn” is a song.", shownPassage("“Turn” is a song."))
        assertEquals("… Already trimmed.", shownPassage("… Already trimmed."))
        assertEquals("1997 saw his first album.", shownPassage("1997 saw his first album."))
        assertEquals("all in small letters", shownPassage("all in small letters"))
    }

    @Test
    fun aShortPassageRepeatsALineOnlyWhole() {
        assertTrue(repeats("Seattle", "From Seattle, Washington."))
        assertFalse(repeats("Seattle band", "From Seattle, Washington."))
        assertFalse(repeats("", "Anything."))
        assertFalse(repeats("Page 1.", "Page 2."))
    }
}
