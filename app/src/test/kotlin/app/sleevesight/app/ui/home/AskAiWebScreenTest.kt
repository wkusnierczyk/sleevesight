package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.DefinitionState
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.GenreReason
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.genres.GenreDefinition
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.lyrics.LyricsSite
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.QuestionCost
import app.sleevesight.core.pipeline.SearchWay
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * *Ask AI* with *AI web search* on Home (#801, #802), with the questions faked: the busy line by the
 * way it asks, each value from the web with its page ("From {site}"), an answer from the model's
 * knowledge marked so, a question asked automatically saying so, and a lyrics page among the track's
 * web results shown with no *Ask AI*.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class AskAiWebScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val model = "Claude Haiku 5.5"

    // Each kind's search as the test sets it, and the asks and stops that reached it.
    private val feed =
        object : AiSearchFeed {
            val states = SearchKind.entries.associateWith { MutableStateFlow<AiSearch>(AiSearch.Offered) }
            val asked = mutableListOf<Pair<SearchKind, SearchFacts>>()
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
                asked += kind to facts
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

    private val folkRockDefinition = "Folk rock is a hybrid music genre combining elements of folk and rock."

    // Every genre's definition found, as Wikipedia's would be; no reason offered.
    private val notes =
        object : GenreNotes {
            override fun definition(genre: String): Flow<DefinitionState?> =
                flowOf(
                    DefinitionState.Found(
                        GenreDefinition(folkRockDefinition, "Folk rock", "https://en.wikipedia.org/wiki/$genre"),
                    ),
                )

            override fun reason(
                track: TrackState,
                genre: String,
            ): Flow<GenreReason> = flowOf(GenreReason.Offered)

            override fun explain(
                track: TrackState,
                genre: String,
            ) = Unit
        }

    private val actions = NowPlayingActions(normalizer::normalize, genreNotes = notes, aiSearches = feed)

    private val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)

    private fun state(
        track: TrackState,
        settings: Settings = Settings(),
    ) = NowPlayingState(PipelineView(track, aiReady = true), track.nowPlaying, playback, settings = settings)

    // Sundown with MusicBrainz's [musicBrainz] state and Last.fm's done, no date and no genre.
    private fun empty(musicBrainz: SourceState = done(Outcome.None)): TrackState =
        track(sources = mapOf(SourceId.MUSICBRAINZ to musicBrainz, SourceId.LASTFM to done()))

    private val current = mutableStateOf(state(empty()))

    private fun show(
        initial: NowPlayingState = state(empty()),
        block: () -> Unit,
    ) {
        current.value = initial
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    ArtTheme(null) { errorColour = MaterialTheme.colorScheme.error }
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(current.value, actions) }
                }
            }
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

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun shows(line: String) = compose.onAllNodesWithText(line).fetchSemanticsNodes().isNotEmpty()

    // The font style [tag]'s text is laid out in.
    private fun fontStyle(tag: String): FontStyle? {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts
            .single()
            .layoutInput.style.fontStyle
    }

    // The error colour of Home's theme, as the art's colours leave it (no art here).
    private var errorColour = Color.Unspecified

    // Where a node is in the scrolled column, not clipped to the screen.
    private fun placed(node: SemanticsNode) = Rect(node.positionInRoot, node.size.toSize())

    private fun bounds(tag: String) = placed(compose.onNodeWithTag(tag).fetchSemanticsNode())

    private fun boundsOf(line: String) = placed(compose.onNodeWithText(line).fetchSemanticsNode())

    private val vocabulary = GenreVocabulary.load(JvmResourceReader)
    private val web = SearchWay(web = true, cost = QuestionCost(1, 1))
    private val discogs = "https://www.discogs.com/release/1"

    @Test
    fun underWayWithWebSearchItSaysSoAndFromKnowledgeItAsks() =
        show {
            tap(DATES_CHIP_TAG)
            set(SearchKind.DATES, AiSearch.Searching(web = true))
            compose.onNodeWithTag(AI_SEARCHING_TAG).assertTextEquals("Searching the web with AI…")
            set(SearchKind.DATES, AiSearch.Searching(web = false))
            compose.onNodeWithTag(AI_SEARCHING_TAG).assertTextEquals("Asking AI…")
        }

    @Test
    fun aDateFromTheWebHasItsPageUnderItAndNoKnowledgeMark() =
        show {
            tap(DATES_CHIP_TAG)
            set(
                SearchKind.DATES,
                AiSearch.Answered(SearchAnswer.Dates(album = "1974-01", albumSource = discogs), model, web),
            )
            compose.waitUntil(WAIT_MS) { shows("This album was released in January 1974.") }
            compose.onNodeWithTag(AI_SEARCH_FROM_TAG).assertTextEquals("From discogs.com")
            assertTrue(bounds(AI_SEARCH_FROM_TAG).top >= boundsOf("This album was released in January 1974.").bottom)
            assertEquals(0, count(AI_SEARCH_KNOWLEDGE_TAG))
            compose.onNodeWithTag(AI_SEARCH_BY_TAG).assertTextEquals("by Claude Haiku 5.5")
        }

    @Test
    fun anAnswerFromKnowledgeIsMarkedSoAboveTheCredit() =
        show {
            tap(DATES_CHIP_TAG)
            set(SearchKind.DATES, AiSearch.Answered(SearchAnswer.Dates(album = "1974-01"), model))
            compose.onNodeWithTag(AI_SEARCH_KNOWLEDGE_TAG).assertTextEquals(text(R.string.home_cite_model))
            assertTrue(bounds(AI_SEARCH_BY_TAG).top >= bounds(AI_SEARCH_KNOWLEDGE_TAG).bottom)
            assertEquals(0, count(AI_SEARCH_FROM_TAG))
        }

    @Test
    fun aQuestionAskedAutomaticallySaysSoInItsCreditAndItsNothingLine() =
        show {
            tap(DATES_CHIP_TAG)
            val auto = web.copy(automatic = true)
            set(
                SearchKind.DATES,
                AiSearch.Answered(SearchAnswer.Dates(album = "1974-01", albumSource = discogs), model, auto),
            )
            compose.onNodeWithTag(AI_SEARCH_BY_TAG).assertTextEquals("Asked automatically, by Claude Haiku 5.5")
            set(SearchKind.DATES, AiSearch.NothingFound(model, auto))
            compose
                .onNodeWithTag(AI_SEARCH_NOTHING_TAG)
                .assertTextEquals("Asked automatically: Claude Haiku 5.5 found nothing on the web.")
        }

    @Test
    fun genresFromTheWebShowTheirPagesUnderTheChips() =
        show {
            tap(GENRES_CHIP_TAG)
            val named = listOf(vocabulary.labelsFor(listOf("folk rock"), level = GenreLevel.TRACK).single())
            set(SearchKind.GENRES, AiSearch.Answered(SearchAnswer.Genres(named, listOf(discogs)), model, web))
            compose.onNodeWithTag(AI_SEARCH_FROM_TAG).assertTextEquals("From discogs.com")
        }

    @Test
    fun aLyricsPageAmongTheWebResultsIsShownWithNoAskAi() {
        val genius = "https://genius.com/Gordon-lightfoot-sundown-lyrics"
        val result =
            SourceResult(
                Outcome.SongArticle,
                listOf(
                    EvidenceDraft(SourceId.WEB, Scope.WEB, "A line.", genius, Instant.parse("2026-11-01T10:00:00Z")),
                ),
                Duration.ZERO,
                trackOnly = true,
            )
        val withWeb =
            track(
                sources =
                    mapOf(
                        SourceId.MUSICBRAINZ to done(Outcome.None),
                        SourceId.LASTFM to done(),
                        SourceId.WEB to SourceState.Done(result, cached = false),
                    ),
            )
        show(state(withWeb)) {
            tap(LYRICS_CHIP_TAG)
            assertTrue(shows(text(R.string.home_lyrics_web_note)))
            assertEquals(1, count(lyricsPageTag(LyricsSite.GENIUS.name)))
            assertEquals(0, count(LYRICS_SEARCH_TAG))
            assertEquals(0, count(AI_SEARCH_TAG))
        }
    }

    @Test
    fun theSiteIsTheHostWithoutWww() {
        assertEquals("discogs.com", siteOf("https://www.discogs.com/release/1?x=1"))
        assertEquals("en.wikipedia.org", siteOf("https://en.wikipedia.org/wiki/Grace"))
        assertEquals("not a url", siteOf("not a url"))
    }
}
