package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.ItemNotes
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SearchWay
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.synthesis.CitedPage
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/**
 * The pages the song's explanation cites, in *Sources* (#1067): web lines after the others, numbered
 * on from them (the track's own S1 to S6, then S7, S8 …), named *explanation*, each its page's title
 * or, with none, its site; a page another line has already is that line, keeping its name; a kept
 * explanation lists the same lines. The explanation's citations show those lines' ids; *Read more*
 * lists every page once, in citation order.
 */
@RunWith(RobolectricTestRunner::class)
class LyricsExplanationSourcesTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val at = Instant.parse("2026-11-17T00:00:00Z")

    // The track's six web lines, S1 to S6.
    private val own =
        EvidenceSet.of(
            (1..6).map { EvidenceDraft(SourceId.WEB, Scope.WEB, "Page $it.", "https://example.org/$it", at) },
            JvmHasher,
        )

    private val songfacts = CitedPage("S1", "https://www.songfacts.example/sundown", "Sundown song facts")
    private val untitled = CitedPage("S2", "https://lyricstories.example/sundown", null)
    private val again = CitedPage("S3", "https://example.org/2/", "Page two again")

    private fun answer(vararg pages: CitedPage): SearchAnswer.Explanation {
        val reply = pages.joinToString(" ") { "It says so. [${it.id}]" }
        val ids = pages.map { it.id }.toSet()
        val grounded = (GroundingCheck.check(reply, ids, allowModel = false) as InsightReply.Written).grounded
        return SearchAnswer.Explanation(grounded, pages.toList())
    }

    private fun answered(
        vararg pages: CitedPage,
        keptAt: Instant? = null,
    ) = AiSearch.Answered(answer(*pages), MODEL, SearchWay(web = true), keptAt)

    private fun sources(search: AiSearch): SourcesLines =
        withPages(SourcesLines(own), explainedPages(search), LinePurpose.EXPLANATION)

    @Test
    fun theExplanationsPagesJoinAfterTheWebLinesNumberedOnNamedExplanation() {
        val lines = sources(answered(songfacts, untitled))
        val added = checkNotNull(lines.evidence).lines.drop(own.lines.size)
        assertEquals(listOf("S7", "S8"), added.map { it.id })
        assertEquals(listOf(songfacts.url, untitled.url), added.map { it.url })
        // The line's text: the page's title, or its site where it has none.
        assertEquals(listOf("Sundown song facts", "lyricstories.example"), added.map { it.text })
        assertEquals(mapOf("S7" to LinePurpose.EXPLANATION, "S8" to LinePurpose.EXPLANATION), lines.purposes)
        // A genre's reason is still asked on the lines as they were.
        assertEquals(own, lines.asked)
    }

    @Test
    fun aPageAnotherLineHasIsThatLineAndKeepsItsName() {
        val genre = WebPage("https://en.wikipedia.org/wiki/Folk_rock", null, "Folk rock is a genre.")
        val genres = withPages(SourcesLines(own), listOf(genre), LinePurpose.GENRES)
        val cited = CitedPage("S4", genre.url + "/", "Folk rock - Wikipedia")
        val lines = withPages(genres, explainedPages(answered(again, cited, songfacts)), LinePurpose.EXPLANATION)
        val added = checkNotNull(lines.evidence).lines.drop(own.lines.size)
        // The track's S2 and the genres' S7 not listed again: songfacts alone is new, S8.
        assertEquals(listOf("S7", "S8"), added.map { it.id })
        assertEquals(mapOf("S7" to LinePurpose.GENRES, "S8" to LinePurpose.EXPLANATION), lines.purposes)
        val shown = citedLines(answer(again, cited, songfacts).pages, lines.evidence)
        assertEquals(listOf("S2", "S7", "S8"), shown.values.map { it.shown })
        assertTrue(shown.values.all { it.inSources })
    }

    @Test
    fun aKeptExplanationListsTheSameLines() {
        // Kept as it was stored: its title not yet cleaned, its address with a trailing slash.
        val stored = songfacts.copy(url = songfacts.url + "/", title = "\u200BSundown song facts\u202E")
        val fresh = sources(answered(songfacts, untitled, again))
        val kept = sources(answered(stored, untitled, again, keptAt = at))
        val shown = { lines: SourcesLines ->
            checkNotNull(lines.evidence).lines.map { Triple(it.id, it.text, it.source) }
        }
        assertEquals(shown(fresh), shown(kept))
        assertEquals(fresh.purposes, kept.purposes)
        assertEquals(
            citedLines(answer(songfacts, untitled, again).pages, fresh.evidence).mapValues { it.value.shown },
            citedLines(answer(stored, untitled, again).pages, kept.evidence).mapValues { it.value.shown },
        )
    }

    @Test
    fun aPageOfTheTracksOwnOfAnotherSourceIsItsLine() {
        // The song's Wikipedia article, which the explanation is given from the track's evidence.
        val article = EvidenceDraft(SourceId.WIKIPEDIA, Scope.SONG, "It topped the charts.", WIKI, at)
        val evidence =
            EvidenceSet.of(
                own.lines.map { EvidenceDraft(it.source, it.scope, it.text, it.url, at) } + article,
                JvmHasher,
            )
        val cited = CitedPage("S2", "$WIKI/", "Wikipedia")
        val lines =
            withPages(SourcesLines(evidence), explainedPages(answered(songfacts, cited)), LinePurpose.EXPLANATION)
        // Songfacts alone is new: the article is not listed again, nor named *explanation*.
        val added = checkNotNull(lines.evidence).lines.filter { it.id !in evidence.ids.toSet() }
        assertEquals(listOf(songfacts.url), added.map { it.url })
        assertEquals(listOf(LinePurpose.EXPLANATION), lines.purposes.values.toList())
        val chip = citedLines(listOf(cited), lines.evidence).getValue("S2")
        assertEquals("W1", chip.shown)
        assertTrue(chip.inSources)
        assertNull(lines.purposes["W1"])
    }

    @Test
    fun noAnswerListsNoPage() {
        val none = listOf(AiSearch.None, AiSearch.Offered, AiSearch.Searching(web = true), AiSearch.NothingFound(MODEL))
        for (search in none) {
            assertTrue(explainedPages(search).isEmpty())
        }
        assertTrue(explainedPages(AiSearch.Answered(SearchAnswer.Lyrics(emptyList()), MODEL)).isEmpty())
    }

    @Test
    fun aTitleShowsWithoutWhatShowsNothingOrTurnsTheTextAround() {
        assertEquals("Sundown: song facts", shownTitle("Sundown:​ song\n facts‮"))
        assertNull(shownTitle("​ ⁠"))
        assertNull(shownTitle(null))
        // A joiner that shapes what shows is kept: an emoji's, a Persian word's.
        assertEquals(
            "Family \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67",
            shownTitle("Family \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"),
        )
        assertEquals(
            "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645",
            shownTitle("\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645"),
        )
        val blank = CitedPage("S1", "https://www.music.example/x", "​ ⁠")
        assertEquals("music.example", explainedPages(answered(blank)).single().text)
    }

    @Test
    fun aKeptTitleOfInvisibleCharactersAloneShowsTheSiteAsItsLine() {
        // A tag character (a surrogate pair), zero-width marks, a bidi override: each alone, and together.
        val titles = listOf("\uDB40\uDC20", "\u200B\u2060\uFEFF", "\u202E\u2066", "\uDB40\uDC20 \u200B\u202E")
        for (title in titles) {
            val page = CitedPage("S1", "https://www.music.example/x", title)
            val line = sources(answered(page, keptAt = at)).evidence?.get("S7")
            assertEquals(title, "music.example", line?.text)
        }
    }

    @Test
    fun withNoLineInSourcesACitationKeepsItsOwnIdAndPage() {
        val shown = citedLines(listOf(songfacts), null).getValue("S1")
        assertEquals("S1", shown.shown)
        assertFalse(shown.inSources)
    }

    @Test
    fun sourcesListsTheExplanationsPagesNamedExplanation() {
        val track = HomeFixtures.track(evidence = own)
        val feed = Feed(answered(songfacts))
        var lines: SourcesLines? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    val shown = sourcesLines(ItemNotes.None, track, GenreNotes.None, feed)
                    lines = shown
                    Column {
                        for (line in checkNotNull(shown.evidence).lines.drop(6)) Text("${line.id} ${shown.what(line)}")
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText("S7 explanation").assertExists()
            assertEquals(songfacts.url, checkNotNull(lines?.evidence)["S7"]?.url)
            // Read only: reading it never asks.
            assertTrue(SearchKind.EXPLAIN in feed.read)
        }
    }

    @Test
    fun readMoreListsEachPageOnceInCitationOrder() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent { ReadMore(answer(untitled, songfacts, CitedPage("S3", untitled.url + "/", "Again"))) }
            }
            compose.waitForIdle()
            compose
                .onNodeWithText("Read more:", substring = true)
                .assertTextEquals("Read more: lyricstories.example · songfacts.example")
            assertEquals(
                listOf("lyricstories.example" to untitled.url, "songfacts.example" to songfacts.url),
                compose.linkedRanges(LYRICS_READ_MORE_TAG),
            )
        }
    }

    // A feed that has [answer] for the explanation, and records each read.
    private class Feed(
        private val answer: AiSearch,
    ) : AiSearchFeed {
        val read = mutableListOf<SearchKind>()

        override fun search(
            track: TrackState,
            kind: SearchKind,
            genre: String?,
        ): Flow<AiSearch> = flowOf(AiSearch.None)

        override fun answered(
            track: TrackState,
            kind: SearchKind,
        ): Flow<AiSearch> {
            if (kind == SearchKind.EXPLAIN) read += kind
            return flowOf(if (kind == SearchKind.EXPLAIN) answer else AiSearch.None)
        }

        override fun ask(
            track: TrackState,
            kind: SearchKind,
            facts: SearchFacts,
            automatic: Boolean,
            genre: String?,
        ): Boolean = error("asked")

        override fun stop(
            track: TrackState,
            kind: SearchKind,
            genre: String?,
        ) = Unit
    }

    private companion object {
        const val MODEL = "Claude Haiku 5.5"
        const val WIKI = "https://en.wikipedia.org/wiki/Sundown"
    }
}
