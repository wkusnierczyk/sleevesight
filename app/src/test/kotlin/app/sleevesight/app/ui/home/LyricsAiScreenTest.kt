package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SearchWay
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WorkLyrics
import app.sleevesight.core.synthesis.CitedPage
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The *Lyrics* panel's two AI buttons (#908), with the searches faked: *Find the lyrics* with the AI
 * mark where *Ask AI* was, its own lines; *Explain the song* last for every track with lyrics, a page
 * found or not, not while looking nor for an instrumental, nor when not offered; the two as wide as
 * each other; and the explanation, each citation its page's line in *Sources* (#1067), credited to the
 * model, then *Read more* with the pages' sites.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class LyricsAiScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val model = "Claude Haiku 5.5"

    private val feed =
        object : AiSearchFeed {
            val states = SearchKind.entries.associateWith { MutableStateFlow<AiSearch>(AiSearch.Offered) }
            val asked = mutableListOf<SearchKind>()
            val stopped = mutableListOf<SearchKind>()

            override fun search(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ): Flow<AiSearch> = states.getValue(kind)

            override fun ask(
                track: TrackState,
                kind: SearchKind,
                facts: SearchFacts,
                automatic: Boolean,
                genre: String?,
            ): Boolean {
                asked += kind
                return true
            }

            override fun stop(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ) {
                stopped += kind
            }
        }

    private val actions = NowPlayingActions(normalizer::normalize, aiSearches = feed)

    private val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)

    // MusicBrainz found the song, and says [lyrics] of it.
    private fun lyrics(lyrics: WorkLyrics = WorkLyrics()) =
        SourceState.Done(
            SourceResult(Outcome.SongArticle, emptyList(), 10.milliseconds, lyrics = lyrics),
            cached = false,
        )

    private fun state(musicBrainz: SourceState = lyrics()): NowPlayingState {
        val track = track(sources = mapOf(SourceId.MUSICBRAINZ to musicBrainz, SourceId.LASTFM to done()))
        return NowPlayingState(PipelineView(track, aiReady = true), track.nowPlaying, playback, settings = Settings())
    }

    private val current = mutableStateOf(state())

    private fun show(
        initial: NowPlayingState = state(),
        block: () -> Unit,
    ) {
        current.value = initial
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(current.value, actions) }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag(LYRICS_CHIP_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            block()
        }
    }

    private fun set(
        kind: SearchKind,
        search: AiSearch,
    ) {
        feed.states.getValue(kind).value = search
        compose.waitForIdle()
    }

    private val find = AI_SEARCH_TAG
    private val explain = explainTag(AI_SEARCH_TAG)

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun shows(line: String) = compose.onAllNodesWithText(line).fetchSemanticsNodes().isNotEmpty()

    private fun placed(node: SemanticsNode) = Rect(node.positionInRoot, node.size.toSize())

    private fun bounds(tag: String) = placed(compose.onNodeWithTag(tag).fetchSemanticsNode())

    // A button whose tap is named [action] for a screen reader, read as "{label}, uses AI".
    private fun assertButton(
        tag: String,
        label: Int,
        action: Int,
    ) {
        compose
            .onNodeWithTag(tag)
            .performScrollTo()
            .assertTextEquals(text(label))
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("${text(label)}, uses AI")),
            ).assert(
                SemanticsMatcher("click labelled \"${text(action)}\"") {
                    it.config.getOrNull(SemanticsActions.OnClick)?.label == text(action)
                },
            )
        assertEquals(
            1,
            compose.onAllNodesWithTag(AI_MARK_TAG, useUnmergedTree = true).fetchSemanticsNodes().count {
                placed(it).overlaps(bounds(tag))
            },
        )
    }

    @Test
    fun theTextsAreThePickedOnes() {
        assertEquals("Find the lyrics", text(R.string.home_lyrics_find))
        assertEquals("search the web for the song’s lyrics page, a paid call", text(R.string.home_lyrics_find_action))
        assertEquals("Searching the web for the lyrics…", text(R.string.home_lyrics_finding))
        assertEquals(
            "Claude Haiku 5.5 found no lyrics on the web.",
            app.getString(R.string.home_lyrics_found_none, model),
        )
        assertEquals("Explain the song", text(R.string.home_lyrics_explain))
        assertEquals(
            "ask your AI service what the song is about, a paid call",
            text(R.string.home_lyrics_explain_action),
        )
        assertEquals("Reading about the song…", text(R.string.home_lyrics_explaining))
        assertEquals(
            "Claude Haiku 5.5 found nothing to explain the song.",
            app.getString(R.string.home_lyrics_explain_none, model),
        )
        assertEquals("Read more: %1\$s", text(R.string.home_lyrics_read_more))
        assertEquals("explanation", text(R.string.home_line_purpose_explanation))
        // Dates and Genres keep *Ask AI*.
        assertEquals("Ask AI", text(R.string.home_ai_search))
    }

    @Test
    fun withNoPageFindTheLyricsThenExplainTheSongEachMarkedAndAsWideAsTheOther() =
        show {
            assertButton(find, R.string.home_lyrics_find, R.string.home_lyrics_find_action)
            assertButton(explain, R.string.home_lyrics_explain, R.string.home_lyrics_explain_action)
            assertTrue(bounds(find).top >= bounds(LYRICS_SEARCH_TAG).bottom)
            assertTrue(bounds(explain).top >= bounds(find).bottom)
            assertEquals(bounds(find).width, bounds(explain).width, 1f)
            assertEquals(bounds(find).height, bounds(explain).height, 1f)
            compose.onNodeWithTag(explain).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(find).performClick()
            compose.waitForIdle()
            assertEquals(listOf(SearchKind.EXPLAIN, SearchKind.LYRICS), feed.asked)
        }

    @Test
    fun withAPageFromMusicBrainzOnlyExplainTheSongAfterTheLinks() =
        show(state(lyrics(WorkLyrics(listOf("https://genius.com/Gordon-lightfoot-sundown-lyrics"))))) {
            assertEquals(0, count(find))
            assertEquals(1, count(explain))
            assertTrue(bounds(explain).top >= bounds(lyricsPageTag("GENIUS")).bottom)
        }

    @Test
    fun noExplainWhileLookingForAnInstrumentalOrWhenNotOffered() {
        for (musicBrainz in listOf(SourceState.Pending, lyrics(WorkLyrics(instrumental = true)))) {
            show(state(musicBrainz)) { assertEquals(0, count(explain)) }
        }
        // AI web search off: not offered at all; the search still is.
        feed.states.getValue(SearchKind.EXPLAIN).value = AiSearch.None
        show {
            assertEquals(0, count(explain))
            assertEquals(1, count(find))
        }
    }

    @Test
    fun withNoAiServiceExplainIsDisabledAndAScreenReaderSaysWhy() =
        show {
            set(SearchKind.EXPLAIN, AiSearch.Unready)
            compose
                .onNodeWithTag(explain)
                .performScrollTo()
                .assertIsNotEnabled()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        text(R.string.home_ai_search_unready),
                    ),
                )
            compose.onNodeWithTag(find).assertIsEnabled()
        }

    @Test
    fun eachRunsWithItsOwnLineAndStopAndFindsNothingInItsOwnWords() =
        show {
            set(SearchKind.LYRICS, AiSearch.Searching(web = true))
            compose.onNodeWithTag(AI_SEARCHING_TAG).assertTextEquals(text(R.string.home_lyrics_finding))
            set(SearchKind.LYRICS, AiSearch.Searching(web = false))
            compose.onNodeWithTag(AI_SEARCHING_TAG).assertTextEquals(text(R.string.home_ai_asking))
            set(SearchKind.EXPLAIN, AiSearch.Searching(web = true))
            compose.onNodeWithTag(explainTag(AI_SEARCHING_TAG)).assertTextEquals(text(R.string.home_lyrics_explaining))
            compose.onNodeWithTag(explainTag(AI_SEARCH_STOP_TAG)).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(SearchKind.EXPLAIN), feed.stopped)
            set(SearchKind.LYRICS, AiSearch.NothingFound(model, SearchWay(web = true)))
            compose
                .onNodeWithTag(AI_SEARCH_NOTHING_TAG)
                .assertTextEquals(app.getString(R.string.home_lyrics_found_none, model))
            set(SearchKind.LYRICS, AiSearch.NothingFound(model))
            compose
                .onNodeWithTag(
                    AI_SEARCH_NOTHING_TAG,
                ).assertTextEquals(app.getString(R.string.home_ai_nothing, model))
            set(SearchKind.EXPLAIN, AiSearch.NothingFound(model, SearchWay(web = true)))
            compose
                .onNodeWithTag(explainTag(AI_SEARCH_NOTHING_TAG))
                .assertTextEquals(app.getString(R.string.home_lyrics_explain_none, model))
        }

    // An explanation citing [songfacts] and [interview], and the track's own web page (`example.org`,
    // *Sources*' S1, with a trailing slash), on a track whose evidence has that web line.
    private val songfacts = CitedPage("S1", "https://www.songfacts.example/sundown", "Sundown:\u200B song facts\u202E")
    private val interview = CitedPage("S2", "https://musicmag.example/interview", "\u200B \u2060")
    private val own = CitedPage("S3", "https://example.org/sundown/", "Sundown went gold")

    private fun explanation(): SearchAnswer.Explanation {
        val reply = "It is about jealousy. [S1] It reads as a warning. The title is a time of day. [S2] It sold. [S3]"
        val grounded =
            (GroundingCheck.check(reply, setOf("S1", "S2", "S3"), allowModel = false) as InsightReply.Written).grounded
        return SearchAnswer.Explanation(grounded, listOf(songfacts, interview, own))
    }

    private fun withWeb(): NowPlayingState {
        val track =
            track(
                sources = mapOf(SourceId.MUSICBRAINZ to lyrics(), SourceId.LASTFM to done()),
                evidence = HomeFixtures.withWeb,
            )
        return NowPlayingState(PipelineView(track, aiReady = true), track.nowPlaying, playback, settings = Settings())
    }

    @Test
    fun theExplanationCitesSourcesLinesThenTheModelThenReadMore() =
        show(withWeb()) {
            set(SearchKind.EXPLAIN, AiSearch.Answered(explanation(), model, SearchWay(web = true)))
            assertEquals(0, count(explain))
            val prose = compose.onNodeWithTag(LYRICS_EXPLANATION_TAG).fetchSemanticsNode()
            val said = prose.config[SemanticsProperties.ContentDescription].single()
            // Each citation read by its line in *Sources* (#1067): the pages new to it S2 and S3 after
            // the track's web line S1, the track's own page that line.
            assertEquals(
                // A citation after a stop is said before it, as it shows (#1061).
                "It is about jealousy (Source S2). It reads as a warning. (not cited) " +
                    "The title is a time of day (Source S3). It sold (Source S1).",
                said,
            )
            val actions = prose.config[SemanticsActions.CustomActions]
            assertEquals(listOf("Source S2", "Source S3", "Source S1"), actions.map { it.label })
            assertTrue(placed(prose).top >= bounds(explainTag(AI_SEARCH_RULE_TAG)).bottom)
            // *Pages cited* is gone; *Read more* is last, after the credit.
            assertFalse(shows("Pages cited"))
            compose.onNodeWithTag(explainTag(AI_SEARCH_BY_TAG)).assertTextEquals("by Claude Haiku 5.5")
            assertTrue(bounds(explainTag(AI_SEARCH_BY_TAG)).top >= placed(prose).bottom)
            assertTrue(bounds(LYRICS_READ_MORE_TAG).top >= bounds(explainTag(AI_SEARCH_BY_TAG)).bottom)
            compose
                .onNodeWithTag(LYRICS_READ_MORE_TAG)
                .assertTextEquals("Read more:\u00A0songfacts.example\u00A0· musicmag.example\u00A0· example.org")
            // Each site a link of its own, "Read more:" plain text, in citation order.
            assertEquals(
                listOf(
                    "songfacts.example" to songfacts.url,
                    "musicmag.example" to interview.url,
                    "example.org" to own.url,
                ),
                compose.linkedRanges(LYRICS_READ_MORE_TAG),
            )
        }

    @Test
    fun aKeptExplanationReadsTheSameAndReadMoreComesAfterTheKeptLine() =
        show(withWeb()) {
            val kept = Instant.parse("2026-11-01T12:00:00Z")
            set(SearchKind.EXPLAIN, AiSearch.Answered(explanation(), model, SearchWay(web = true), keptAt = kept))
            val said =
                compose
                    .onNodeWithTag(LYRICS_EXPLANATION_TAG)
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.ContentDescription]
                    .single()
            assertTrue(said, said.endsWith("(Source S3). It sold (Source S1)."))
            assertTrue(bounds(LYRICS_READ_MORE_TAG).top >= bounds(AI_KEPT_TAG).bottom)
        }

    @Test
    fun aCitationOpensSourcesAtItsLine() =
        show(withWeb()) {
            set(SearchKind.EXPLAIN, AiSearch.Answered(explanation(), model, SearchWay(web = true)))
            // *Sources* folded: the explanation's lines not shown until a citation reaches them.
            assertEquals(0, count(LINE_PREFIX + "S3"))
            compose
                .onNodeWithTag(LYRICS_EXPLANATION_TAG)
                .performScrollTo()
                .performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == CITE_PREFIX + "S3" }
            compose.waitForIdle()
            compose.onNodeWithTag(LINE_PREFIX + "S3").assertExists()
            // Named by what it was read for, then its site; its text the page's title, or its site.
            assertTrue(shows("explanation · musicmag.example"))
            assertTrue(shows("explanation · songfacts.example"))
            assertTrue(shows("Sundown: song facts"))
        }
}
