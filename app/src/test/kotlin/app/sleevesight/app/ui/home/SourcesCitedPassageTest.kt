package app.sleevesight.app.ui.home

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.sources.WebPage
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

/**
 * A citation lands on the passage it cites (#1435): a page's passage other than the one *Sources*
 * shows for its address is a line of its own, and the chip opens it.
 */
class SourcesCitedPassageTest {
    private val at = Instant.fromEpochMilliseconds(1)

    private val spotify = "https://open.spotify.com/track/turn"
    private val play = "Play on Spotify. Turn, a song by Damien Jurado."
    private val quote = "Exclaim! calls Turn a hushed americana ballad, its pedal steel drifting under the voice."

    private fun lines(vararg pages: Pair<String, String>) =
        SourcesLines(
            EvidenceSet.of(
                pages.map { (url, text) -> EvidenceDraft(SourceId.WEB, Scope.WEB, text, url, at) },
                JvmHasher,
            ),
        )

    // A reason's own evidence: its first line, then the page it cited, S2 in its numbering.
    private fun reason(
        url: String,
        text: String,
    ) = lines("https://example.org/own" to "Own page.").evidence!!.with(
        listOf(EvidenceDraft(SourceId.WEB, Scope.WEB, text, url, at)),
        JvmHasher,
    )

    @Test
    fun aPassageOfAPageALineShowsAnotherPassageOfIsListedAndCitedAsItsOwnLine() {
        val pages = listOf(WebPage(spotify, null, quote))
        val listed = withPages(lines(spotify to play), pages, LinePurpose.GENRES, passages = true)
        assertEquals(listOf(play, quote), listed.evidence!!.lines.map { it.text })
        assertEquals("S2", webLineOf(spotify, listed.evidence, listed.moved, quote))
        assertEquals("S1", webLineOf(spotify, listed.evidence, listed.moved, play))
        // The reason's citation of the quote opens the quote's line, not the page's "Play on …".
        assertEquals("S2", shownLine("S2", reason(spotify, quote), listed.evidence, listed.moved))
        assertEquals("S1", shownLine("S2", reason(spotify, play), listed.evidence, listed.moved))
    }

    @Test
    fun twoPassagesOfOnePageInOneBatchAreEachListedAndCited() {
        val pages = listOf(WebPage(spotify, null, play), WebPage("$spotify/", null, quote))
        val listed = withPages(lines("https://example.org/own" to "Own page."), pages, LinePurpose.GENRES, true)
        val added = listed.evidence!!.lines.drop(1)
        assertEquals(listOf(play, quote), added.map { it.text })
        assertEquals("S2", webLineOf(spotify, listed.evidence, listed.moved, play))
        assertEquals("S3", webLineOf(spotify, listed.evidence, listed.moved, quote))
    }

    @Test
    fun aRepeatOfTheSecondPassageOfAnAddressIsCitedAsThatPassagesLine() {
        val part = WebPage(spotify, null, "a hushed americana ballad, its pedal steel drifting")
        val pages = listOf(WebPage(spotify, null, play), WebPage(spotify, null, quote), part)
        val listed = withPages(lines("https://example.org/own" to "Own page."), pages, LinePurpose.GENRES, true)
        assertEquals(3, listed.evidence!!.lines.size)
        assertEquals(mapOf(MovedPage(spotify, passageKey(part.text)) to "S3"), listed.moved)
        assertEquals("S3", webLineOf(spotify, listed.evidence, listed.moved, part.text))
    }

    @Test
    fun aPassageALineAtItsAddressHoldsIsThatLine() {
        val listed =
            withPages(
                lines(spotify to "$play $quote"),
                listOf(WebPage(spotify, null, quote)),
                LinePurpose.VOCALS_CHECK,
                passages = true,
            )
        assertEquals(1, listed.evidence!!.lines.size)
        assertEquals("S1", webLineOf(spotify, listed.evidence, listed.moved, quote))
    }

    @Test
    fun aPageWithoutAPassageAtAnAddressALineHasIsThatLine() {
        val bare = WebPage(spotify, null, spotify)
        val listed = withPages(lines(spotify to play), listOf(bare), LinePurpose.GENRES, passages = true)
        assertEquals(1, listed.evidence!!.lines.size)
        val title = WebPage(spotify, "Turn", "Turn")
        val titled = withPages(lines(spotify to play), listOf(title), LinePurpose.EXPLANATION)
        assertEquals(1, titled.evidence!!.lines.size)
        assertEquals("S1", webLineOf(spotify, titled.evidence))
    }

    private val song = "Turn, a song by Damien Jurado."

    @Test
    fun aPassageItsPagesLineAndAnotherBothHoldIsItsPagesLine() {
        // Another source's line, and another address's, that hold the passage before Spotify's line does.
        val others =
            listOf(
                EvidenceDraft(SourceId.LASTFM, Scope.SONG, song, null, at),
                EvidenceDraft(SourceId.WEB, Scope.WEB, "Out now: $song", "https://example.org/turn", at),
            )
        others.forEach { other ->
            val own = EvidenceDraft(SourceId.WEB, Scope.WEB, play, spotify, at)
            val lines = SourcesLines(EvidenceSet.of(listOf(other, own), JvmHasher))
            val listed = withPages(lines, listOf(WebPage(spotify, null, song)), LinePurpose.GENRES, true)
            val shown = listed.evidence!!.lines
            assertEquals(2, shown.size)
            val spotifys = shown.single { it.url == spotify }.id
            assertEquals(mapOf(MovedPage(spotify, passageKey(song)) to spotifys), listed.moved)
            assertEquals(spotifys, webLineOf(spotify, listed.evidence, listed.moved, song))
            assertEquals(spotifys, shownLine("S2", reason(spotify, song), listed.evidence, listed.moved))
        }
    }

    @Test
    fun aPassageTwoPagesOfTheBatchHoldIsHeldByThePageAtItsOwnAddress() {
        val other = WebPage("https://example.org/turn", null, "Listen: $song Out now on Secretly Canadian, on vinyl.")
        val part = WebPage(spotify, null, song)
        val pages = listOf(other, WebPage(spotify, null, play), part)
        val listed = withPages(lines("https://example.org/own" to "Own page."), pages, LinePurpose.GENRES, true)
        assertEquals(3, listed.evidence!!.lines.size)
        assertEquals(mapOf(MovedPage(spotify, passageKey(song)) to "S3"), listed.moved)
        assertEquals("S3", webLineOf(spotify, listed.evidence, listed.moved, song))
    }

    @Test
    fun aPageWithoutAPassageIsNotListedAtAnAddressAPassageOfTheBatchHas() {
        val bare = WebPage(spotify, null, spotify)
        val passage = WebPage("$spotify/", null, quote)
        listOf(listOf(bare, passage), listOf(passage, bare)).forEach { pages ->
            val listed = withPages(lines("https://example.org/own" to "Own page."), pages, LinePurpose.GENRES, true)
            val added = listed.evidence!!.lines.drop(1)
            assertEquals(listOf(quote), added.map { it.text })
            assertEquals("S2", webLineOf(spotify, listed.evidence))
        }
    }
}
