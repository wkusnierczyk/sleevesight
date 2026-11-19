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
        assertEquals(mapOf(MovedPage(iheart.url, passageKey(iheart.text)) to "S2"), listed.moved)
    }

    @Test
    fun aPageWhosePassageALineAlreadyListedHoldsIsLeftOutAndCitedAsThatLine() {
        val listed = withPages(lines(bio), listOf(iheart, other), LinePurpose.VOCALS_CHECK, passages = true)
        assertEquals(listOf(other.url), shown(listed).map { it.url })
        assertEquals("S1", webLineOf(iheart.url, listed.evidence, listed.moved, iheart.text))
        // Kept from one call to the next: the explanation's pages after the check's.
        val after = withPages(listed, listOf(WebPage("https://example.org/x", null, "X")), LinePurpose.EXPLANATION)
        assertEquals("S1", webLineOf(iheart.url, after.evidence, after.moved, iheart.text))
    }

    @Test
    fun aPageLeftOutWithNoOtherPageIsCitedAsTheLineThatHoldsIt() {
        // Nothing fresh to list, only the repeat: still where its citations go.
        val listed = withPages(lines(bio), listOf(iheart), LinePurpose.GENRES, passages = true)
        assertEquals(1, listed.evidence!!.lines.size)
        assertEquals("S1", webLineOf(iheart.url, listed.evidence, listed.moved, iheart.text))
    }

    // One address, two highlights: the genre's, held by S1, and the vocals check's, held by S2.
    private val genreLine = "Turn is a folk song with a slow, hushed arrangement and a lone guitar."
    private val vocalsLine = "The singer's voice carries the whole of Turn, from the first verse to the last."
    private val address = "https://www.iheart.com/song/turn"
    private val genrePart = WebPage(address, null, ", a folk song with a slow, hushed arrangement")
    private val vocalsPart = WebPage(address, null, "the whole of Turn, from the first verse")

    @Test
    fun twoPassagesOfOneAddressAreEachCitedAsTheLineThatHoldsIt() {
        val genres = withPages(lines(genreLine, vocalsLine), listOf(genrePart), LinePurpose.GENRES, passages = true)
        val checked = withPages(genres, listOf(vocalsPart), LinePurpose.VOCALS_CHECK, passages = true)
        assertEquals(2, checked.evidence!!.lines.size)
        // The vocals check's passage does not take the genre's place: each its own holder.
        assertEquals("S1", webLineOf(address, checked.evidence, checked.moved, genrePart.text))
        assertEquals("S2", webLineOf(address, checked.evidence, checked.moved, vocalsPart.text))
        // A reason's citation of the genre's passage, by its address and words, shows S1.
        val reason =
            lines("Own page.").evidence!!.with(
                listOf(EvidenceDraft(SourceId.WEB, Scope.WEB, genrePart.text, address, at)),
                JvmHasher,
            )
        assertEquals("S1", shownLine("S2", reason, checked.evidence, checked.moved))
    }

    @Test
    fun aLineListedLaterAtTheAddressNeverTakesTheHoldersPlace() {
        val genres = withPages(lines(genreLine), listOf(genrePart), LinePurpose.GENRES, passages = true)
        // The explanation cites the same address, its title alone: a line of its own, S2.
        val title = WebPage(address, "Turn", "Turn")
        val explained = withPages(genres, listOf(title), LinePurpose.EXPLANATION)
        assertEquals(address, explained.evidence!!["S2"]?.url)
        // The genre's passage is still cited as S1, the line that holds it; the title as S2.
        assertEquals("S1", webLineOf(address, explained.evidence, explained.moved, genrePart.text))
        assertEquals("S2", webLineOf(address, explained.evidence, explained.moved))
    }

    @Test
    fun aPageLeftOutCitedWithAPassageItWasNotLeftOutForShowsNoLine() {
        val listed = withPages(lines(bio), listOf(iheart), LinePurpose.GENRES, passages = true)
        assertEquals(null, webLineOf(iheart.url, listed.evidence, listed.moved))
        assertEquals(null, webLineOf(iheart.url, listed.evidence, listed.moved, "Another highlight."))
    }

    @Test
    fun aDistinctPassageAfterARepeatAtTheSameAddressIsListed() {
        // The list's page at the address repeats S1; a reason's search found another passage there.
        val held = WebPage(address, null, iheart.text)
        val distinct = WebPage(address, null, genreLine)
        val listed = withPages(lines(bio), listOf(held, distinct), LinePurpose.GENRES, passages = true)
        assertEquals(listOf(address), shown(listed).map { it.url })
        assertEquals(genreLine, shown(listed).single().text)
        // The repeat is cited as S1, the distinct passage as its own line, S2.
        assertEquals("S1", webLineOf(address, listed.evidence, listed.moved, held.text))
        assertEquals("S2", webLineOf(address, listed.evidence, listed.moved, distinct.text))
    }

    @Test
    fun aPageWhoseTextIsItsAddressHasNoPassage() {
        // A list kept before passages were: each page's address as its text. The shorter address's
        // words are within the longer's, yet both are pages of their own, shown as they are.
        val artist = WebPage("https://www.allmusic.com/artist/kaleo", null, "https://www.allmusic.com/artist/kaleo")
        val biography =
            WebPage(
                "https://www.allmusic.com/artist/kaleo/biography",
                null,
                "https://www.allmusic.com/artist/kaleo/biography",
            )
        val listed = withPages(lines("Own page."), listOf(artist, biography), LinePurpose.GENRES, passages = true)
        assertEquals(listOf(artist.url, biography.url), shown(listed).map { it.url })
        assertEquals(listOf(artist.url, biography.url), shown(listed).map { it.text })
        assertEquals(emptyMap<MovedPage, String>(), listed.moved)
        assertEquals("S2", webLineOf(artist.url, listed.evidence, listed.moved, artist.text))
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
    fun aPassageThatDiffersInAVowelSignIsKept() {
        // Hindi: Ram sang this song; Rima sang this song. The vowel signs are marks, and words.
        val ram = "राम ने यह गीत गाया।"
        val rima = WebPage("https://example.in/rima", null, "रीमा ने यह गीत गाया।")
        assertFalse(repeats(rima.text, ram))
        assertFalse(repeats(ram, rima.text))
        assertTrue(repeats("राम ने यह गीत गाया", ram))
        val listed = withPages(lines(ram), listOf(rima), LinePurpose.GENRES, passages = true)
        assertEquals(listOf(rima.url), shown(listed).map { it.url })
        assertEquals(emptyMap<MovedPage, String>(), listed.moved)
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
    fun whatOpensASentenceStaysAndIsNoSignOfOneCut() {
        // A symbol before the first word: currency, a number sign.
        assertEquals("$5 million was spent on it.", shownPassage("$5 million was spent on it."))
        assertEquals("#1 on Billboard for six weeks.", shownPassage("#1 on Billboard for six weeks."))
        // Opening quotes of other scripts: German, CJK, a single guillemet.
        assertEquals("„Hallo“, sagte er.", shownPassage("„Hallo“, sagte er."))
        assertEquals("「ターン」は歌です。", shownPassage("「ターン」は歌です。"))
        assertEquals("‹Turn› is a song.", shownPassage("‹Turn› is a song."))
        // Cut mid-sentence: the comma goes, the symbol or the quote after it stays.
        assertEquals("… $5 million was spent.", shownPassage(", $5 million was spent."))
        assertEquals("… #1 on Billboard.", shownPassage("; #1 on Billboard."))
        assertEquals("… „Hallo“, sagte er.", shownPassage("), „Hallo“, sagte er."))
        // A closing quote or a dash before the first word is a sign of one cut.
        assertEquals("… Turn is a song.", shownPassage("” — Turn is a song."))
    }

    @Test
    fun aLetterBeyondTheFirstPlaneIsALetterNotAMark() {
        // 𝔱 and 𝔗 are each a surrogate pair: the title stays, small letters read as mid-sentence.
        assertEquals("… 𝔱𝔲𝔯𝔫 is an instrumental track.", shownPassage("𝔱𝔲𝔯𝔫 is an instrumental track."))
        assertEquals("𝔗𝔲𝔯𝔫 is an instrumental track.", shownPassage("𝔗𝔲𝔯𝔫 is an instrumental track."))
        assertEquals("… 𝔱𝔲𝔯𝔫 is a song.", shownPassage(", 𝔱𝔲𝔯𝔫 is a song."))
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
        assertEquals(
            "S2",
            shownLine(
                "S2",
                reasons.evidence,
                sources,
                mapOf(
                    MovedPage("https://example.net/1", passageKey("Another page.")) to "S2",
                ),
            ),
        )
    }
}
