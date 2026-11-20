package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
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
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.lyrics.LyricsLink
import app.sleevesight.core.lyrics.LyricsSite
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SearchWay
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.sources.WorkLyrics
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchFacts
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.milliseconds

/**
 * What *Ask AI automatically* asked says so (#956), in *Dates*, *Genres* and *Lyrics*, found or not:
 * "; asked automatically." at the end of the credit or the nothing line, in place of "Asked
 * automatically: " before it, a link that opens the settings at *Ask AI automatically*, which a screen
 * reader names "open the Ask AI automatically setting". Asked by a tap, the lines as before, no note.
 *
 * The texts are written out, not read from the strings, so they are checked word for word. Drawn as on
 * a phone (native graphics), so the words are laid out where a finger finds the link.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoAskedNoteScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val model = "Claude Haiku 5.5"

    private val feed =
        object : AiSearchFeed {
            // The spelling check not offered (#1293), so each panel's search shows.
            val states =
                SearchKind.entries.associateWith {
                    MutableStateFlow(if (it == SearchKind.SPELLING) AiSearch.None else AiSearch.Offered)
                }

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
            ): Boolean = true

            override fun stop(
                track: TrackState,
                kind: SearchKind,
                genre: String?,
            ) = Unit
        }

    // Where the links opened the settings.
    private val opened = mutableListOf<SettingsTarget>()

    private val actions =
        NowPlayingActions(normalizer::normalize, aiSearches = feed, openSettingsAt = { opened += it })

    private val playback = PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING)

    // MusicBrainz found the song, and links to no lyrics page; Last.fm done; no date, no genre.
    private val empty: TrackState =
        track(
            sources =
                mapOf(
                    SourceId.MUSICBRAINZ to
                        SourceState.Done(
                            SourceResult(Outcome.SongArticle, emptyList(), 10.milliseconds, lyrics = WorkLyrics()),
                            cached = false,
                        ),
                    SourceId.LASTFM to done(),
                ),
        )

    private fun show(block: () -> Unit) {
        val state =
            NowPlayingState(PipelineView(empty, aiReady = true), empty.nowPlaying, playback, settings = Settings())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
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

    private fun open(chip: String) {
        compose.onNodeWithTag(chip).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    // What each panel's search found, as it shows it.
    private fun found(kind: SearchKind): SearchAnswer =
        when (kind) {
            SearchKind.DATES -> SearchAnswer.Dates(album = "1974-01")
            SearchKind.GENRES ->
                SearchAnswer.Genres(vocabulary.labelsFor(listOf("folk rock"), level = GenreLevel.TRACK))
            else ->
                SearchAnswer.Lyrics(
                    listOf(LyricsLink(LyricsSite.GENIUS, "https://genius.com/Gordon-lightfoot-sundown-lyrics")),
                )
        }

    private val panels =
        listOf(
            SearchKind.DATES to DATES_CHIP_TAG,
            SearchKind.GENRES to GENRES_CHIP_TAG,
            SearchKind.LYRICS to LYRICS_CHIP_TAG,
        )

    private val tap = SearchWay()
    private val tapWeb = SearchWay(web = true)
    private val auto = SearchWay(automatic = true)
    private val autoWeb = SearchWay(automatic = true, web = true)

    @Test
    fun theTextsAreThePickedOnes() {
        assertEquals("asked automatically", app.getString(R.string.home_ai_auto_link))
        assertEquals("open the Ask AI automatically setting", app.getString(R.string.home_ai_auto_link_action))
    }

    @Test
    fun foundAnswersAskedAutomaticallySaySoAtTheCreditsEndAndByATapDoNot() {
        for ((kind, chip) in panels) {
            show {
                open(chip)
                set(kind, AiSearch.Answered(found(kind), model, auto))
                compose
                    .onNodeWithTag(AI_SEARCH_BY_TAG)
                    .assertTextEquals("Written by Claude Haiku 5.5; asked automatically.")
                assertEquals(kind.name, 1, count(AUTO_ASKED_LINK_TAG))
                set(kind, AiSearch.Answered(found(kind), model, tap))
                compose.onNodeWithTag(AI_SEARCH_BY_TAG).assertTextEquals("Written by Claude Haiku 5.5.")
                assertEquals(kind.name, 0, count(AUTO_ASKED_LINK_TAG))
            }
        }
    }

    @Test
    fun theCreditKeepsItsEffortBeforeTheNote() =
        show {
            open(DATES_CHIP_TAG)
            val medium = AiSearch.Answered(found(SearchKind.DATES), model, auto, effort = ReasoningEffort.MEDIUM)
            set(SearchKind.DATES, medium)
            compose
                .onNodeWithTag(AI_SEARCH_BY_TAG)
                .assertTextEquals("Written by Claude Haiku 5.5 with medium effort; asked automatically.")
        }

    @Test
    fun nothingLinesAskedAutomaticallyEndWithTheNoteInEachPanelAndByATapAsBefore() {
        val web =
            mapOf(
                SearchKind.DATES to "Claude Haiku 5.5 found nothing on the web",
                SearchKind.GENRES to "Claude Haiku 5.5 found nothing on the web",
                SearchKind.LYRICS to "Claude Haiku 5.5 found no lyrics on the web",
            )
        val known = "Claude Haiku 5.5 couldn’t say from its own knowledge"
        for ((kind, chip) in panels) {
            show {
                open(chip)
                for ((way, line) in listOf(
                    autoWeb to "${web.getValue(kind)}; asked automatically.",
                    auto to "$known; asked automatically.",
                    tapWeb to "${web.getValue(kind)}.",
                    tap to "$known.",
                )) {
                    set(kind, AiSearch.NothingFound(model, way))
                    compose.onNodeWithTag(AI_SEARCH_NOTHING_TAG).assertTextEquals(line)
                    assertEquals("$kind $way", if (way.automatic) 1 else 0, count(AUTO_ASKED_LINK_TAG))
                }
            }
        }
    }

    @Test
    fun theLinkOpensTheSettingAtAskAiAutomatically() =
        show {
            open(DATES_CHIP_TAG)
            set(SearchKind.DATES, AiSearch.NothingFound(model, autoWeb))
            // A tap on the words, as a finger gives it, follows the text's link.
            // Over the line's last words, inside it.
            val link = compose.onNodeWithTag(AUTO_ASKED_LINK_TAG).fetchSemanticsNode().boundsInRoot
            val line = compose.onNodeWithTag(AI_SEARCH_NOTHING_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue(link.width > 0f && link.left > line.left && link.right <= line.right)
            compose.onNodeWithTag(AUTO_ASKED_LINK_TAG).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(listOf(SettingsTarget.searchWithAi), opened)
            assertEquals(SettingsTarget(SettingsTarget.searchWithAi.section, "search_with_ai"), opened.single())
        }

    @Test
    fun aScreenReaderReadsTheLineThenTheLinkNamedWithWhereItGoes() =
        show {
            open(GENRES_CHIP_TAG)
            set(SearchKind.GENRES, AiSearch.Answered(found(SearchKind.GENRES), model, auto))
            // The line, read whole, has no action of its own; the link is a stop of its own after it.
            val line = compose.onNodeWithTag(AI_SEARCH_BY_TAG).fetchSemanticsNode()
            assertEquals(null, line.config.getOrElseNullable(SemanticsActions.OnClick) { null })
            val link = compose.onNodeWithTag(AUTO_ASKED_LINK_TAG)
            link.assertTextEquals("asked automatically")
            val click = link.fetchSemanticsNode().config[SemanticsActions.OnClick]
            assertEquals("open the Ask AI automatically setting", click.label)
            link.performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
            assertEquals(listOf(SettingsTarget.searchWithAi), opened)
        }
}
