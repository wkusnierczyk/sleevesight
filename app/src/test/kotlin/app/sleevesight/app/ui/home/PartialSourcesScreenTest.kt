package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.requestFocus
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.wikipediaLine
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.report.REPORT_TAG
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreRow
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home when a source did not answer (#1121), so a short result does not look complete: under the
 * insight's text, "MusicBrainz could not be reached; some evidence for the insight may be missing.";
 * in *Genres*, "…; some genres may be missing."; and *Sources*' note, "MusicBrainz could not be
 * reached.": each with *Retry* beside it, which asks again the sources that did not answer, two
 * named together ("Last.fm and MusicBrainz") and asked in one tap. Once they answer, the lines go.
 * Under both layouts, and at twice the font size. *Dates* and *Lyrics* say what they said before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class PartialSourcesScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private val key = RunKey.of(normalizer, sundown)
    private val musicBrainz get() = text(R.string.settings_source_musicbrainz)
    private val lastFm get() = text(R.string.settings_source_lastfm)
    private val both get() = text(R.string.home_sources_two, lastFm, musicBrainz)

    private val folk =
        TrackGenres(
            listOf(
                GenreRow(
                    GenreLevel.TRACK,
                    null,
                    listOf(
                        TrackLabel(
                            GenreLabel("folk", "Folk", "folk", level = GenreLevel.TRACK),
                            listOf(LabelCredit(LabelSource.LASTFM, "https://example.org/folk")),
                        ),
                    ),
                ),
            ),
        )

    private val insight = written("It topped the charts [W1].")

    // Sundown with an insight written ([insight]), the sources [unreachable] not reached, the others
    // answered, Last.fm with Folk.
    private fun sundownWith(
        unreachable: Set<SourceId>,
        insight: InsightState = this.insight,
    ): TrackState {
        val answered =
            mapOf(
                SourceId.MUSICBRAINZ to done(),
                SourceId.WIKIPEDIA to done(evidence = listOf(wikipediaLine)),
                SourceId.LASTFM to done(),
            )
        val sources =
            answered.mapValues { (source, state) ->
                if (source in
                    unreachable
                ) {
                    done(Outcome.Unreachable)
                } else {
                    state
                }
            }
        val genres = if (SourceId.LASTFM in unreachable) TrackGenres() else folk
        return track(insight = insight, sources = sources).copy(genres = genres)
    }

    private fun state(
        track: TrackState,
        settings: Settings = Settings(),
        offered: Set<SourceId> =
            track.sources
                .filterValues {
                    (it as? SourceState.Done)?.result?.outcome ==
                        Outcome.Unreachable
                }.keys,
    ) = NowPlayingState(
        PipelineView(track, aiReady = true, askAgainKey = key.takeIf { offered.isNotEmpty() }, askable = offered),
        track.nowPlaying,
        PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
        settings = settings,
    )

    private val current = mutableStateOf(state(sundownWith(setOf(SourceId.MUSICBRAINZ))))

    // *Retry*'s taps: the run, the sources asked again, the track.
    private val retried = mutableListOf<Triple<RunKey, Set<SourceId>, NowPlaying>>()
    private val actions =
        NowPlayingActions(normalizer::normalize, askAgain = { key, sources, shown ->
            retried +=
                Triple(key, sources, shown)
        })

    private fun show(
        initial: NowPlayingState,
        page: HomePage? = null,
        block: () -> Unit,
    ) {
        current.value = initial
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    if (page != null) {
                        NowPlayingPageContent(page, current.value, actions) {}
                    } else {
                        Column(
                            Modifier.verticalScroll(rememberScrollState()),
                        ) { NowPlayingContent(current.value, actions) }
                    }
                }
            }
            compose.waitForIdle()
            block()
        }
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun countText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun bounds(tag: String) =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun labelled(action: String) =
        SemanticsMatcher(
            "click labelled \"$action\"",
        ) { it.config.getOrNull(SemanticsActions.OnClick)?.label == action }

    private fun insightLine(names: String) = text(R.string.home_insight_partial, names)

    private fun genresLine(names: String) = text(R.string.home_genres_partial, names)

    @Test
    fun theTextsAreThePicksWordForWord() {
        assertEquals(
            "MusicBrainz could not be reached; some evidence for the insight may be missing.",
            insightLine(musicBrainz),
        )
        assertEquals("Last.fm and MusicBrainz could not be reached; some genres may be missing.", genresLine(both))
        assertEquals("Last.fm and MusicBrainz could not be reached.", text(R.string.home_source_note_unreachable, both))
    }

    @Test
    fun underTheInsightsTextALineSaysSomeEvidenceMayBeMissingWithRetryAskingTheSourceAgain() =
        show(state(sundownWith(setOf(SourceId.MUSICBRAINZ)))) {
            compose.onNodeWithText(insightLine(musicBrainz)).assertExists()
            // Under the insight's text, above its footer.
            val line = bounds(INSIGHT_PARTIAL_TAG)
            assertTrue(
                compose
                    .onNodeWithText(
                        "It topped the charts",
                        substring = true,
                    ).fetchSemanticsNode()
                    .boundsInRoot.bottom <=
                    line.top,
            )
            assertTrue(line.bottom <= bounds(REPORT_TAG).top)
            // *Retry* beside it, on the same line, at its right.
            val retry = bounds(INSIGHT_RETRY_TAG)
            val message = compose.onNodeWithText(insightLine(musicBrainz)).fetchSemanticsNode().boundsInRoot
            assertTrue(retry.left >= message.right)
            assertEquals(message.center.y, retry.center.y, 1f)
            compose
                .onNodeWithTag(INSIGHT_RETRY_TAG)
                .assertTextEquals(text(R.string.home_retry))
                .assert(labelled("ask MusicBrainz again"))
            tap(INSIGHT_RETRY_TAG)
            assertEquals(listOf(Triple(key, setOf(SourceId.MUSICBRAINZ), sundown)), retried)
        }

    @Test
    fun aTapOnTheInsightsRetryHandsTheFocusToReportAProblemAsTheLineGoes() =
        show(state(sundownWith(setOf(SourceId.MUSICBRAINZ)))) {
            compose.onNodeWithTag(INSIGHT_RETRY_TAG).performScrollTo().requestFocus()
            compose.waitForIdle()
            compose.onNodeWithTag(INSIGHT_RETRY_TAG).assertIsFocused()
            tap(INSIGHT_RETRY_TAG)
            // The pipeline asks MusicBrainz again: no *Retry* offered while it does.
            val asking =
                sundownWith(emptySet()).let {
                    it.copy(
                        sources =
                            it.sources + (SourceId.MUSICBRAINZ to SourceState.Pending),
                    )
                }
            current.value = state(asking)
            compose.waitForIdle()
            assertEquals(0, count(INSIGHT_PARTIAL_TAG))
            compose.onNodeWithTag(REPORT_TAG).assertIsFocused()
        }

    // Sundown while the pipeline asks MusicBrainz again: no *Retry* offered while it does.
    private fun askingMusicBrainz(): NowPlayingState {
        val answered = sundownWith(emptySet())
        return state(answered.copy(sources = answered.sources + (SourceId.MUSICBRAINZ to SourceState.Pending)))
    }

    @Test
    fun aTapOnSourcesRetryHandsTheFocusToThePanelsCloseAsTheNoteGoes() =
        show(state(sundownWith(setOf(SourceId.MUSICBRAINZ)))) {
            tap(SOURCES_CHIP_TAG)
            compose.onNodeWithTag(SOURCES_RETRY_TAG).performScrollTo().requestFocus()
            compose.waitForIdle()
            compose.onNodeWithTag(SOURCES_RETRY_TAG).assertIsFocused()
            tap(SOURCES_RETRY_TAG)
            current.value = askingMusicBrainz()
            compose.waitForIdle()
            assertEquals(0, count(SOURCES_RETRY_TAG))
            compose.onNodeWithTag(SOURCES_PANEL_CLOSE_TAG).assertIsFocused()
        }

    @Test
    fun onSourcesPageATapOnRetryHandsTheFocusToTheBackArrowAsTheNoteGoes() =
        show(
            state(sundownWith(setOf(SourceId.MUSICBRAINZ)), Settings(layout = Layout.SEPARATE_PAGES)),
            HomePage.Sources(),
        ) {
            compose.onNodeWithTag(SOURCES_RETRY_TAG).performScrollTo().requestFocus()
            compose.waitForIdle()
            compose.onNodeWithTag(SOURCES_RETRY_TAG).assertIsFocused()
            tap(SOURCES_RETRY_TAG)
            current.value = askingMusicBrainz()
            compose.waitForIdle()
            assertEquals(0, count(SOURCES_RETRY_TAG))
            // The back arrow is also where a keyboard's focus falls by itself on a page, its first
            // control; the handoff moves a screen reader's there too.
            compose.onNodeWithTag(PAGE_BACK_TAG).assertIsFocused()
        }

    @Test
    fun genresSaysSomeGenresMayBeMissingWithRetryInPlaceOfTheShortLine() =
        show(state(sundownWith(setOf(SourceId.MUSICBRAINZ)))) {
            tap(GENRES_CHIP_TAG)
            compose.onNodeWithText(genresLine(musicBrainz)).assertExists()
            assertEquals(0, countText(text(R.string.home_genre_source_unreachable, musicBrainz)))
            compose.onNodeWithTag(RETRY_TAG).performScrollTo().assert(labelled("ask MusicBrainz again"))
            tap(RETRY_TAG)
            assertEquals(listOf(Triple(key, setOf(SourceId.MUSICBRAINZ), sundown)), retried)
        }

    @Test
    fun sourcesNoteGainsRetryBesideItAndTheRowStaysCouldNotBeReached() =
        show(state(sundownWith(setOf(SourceId.MUSICBRAINZ)))) {
            tap(SOURCES_CHIP_TAG)
            compose
                .onNodeWithTag(SOURCES_NOTE_TAG)
                .performScrollTo()
                .assertTextEquals(text(R.string.home_source_note_unreachable, musicBrainz))
            assertTrue(countText(text(R.string.home_source_unreachable)) >= 1)
            val note = bounds(SOURCES_NOTE_TAG)
            val retry = bounds(SOURCES_RETRY_TAG)
            assertTrue(retry.left >= note.right)
            compose
                .onNodeWithTag(SOURCES_RETRY_TAG)
                .assertTextEquals(text(R.string.home_retry))
                .assert(labelled("ask MusicBrainz again"))
            tap(SOURCES_RETRY_TAG)
            assertEquals(listOf(Triple(key, setOf(SourceId.MUSICBRAINZ), sundown)), retried)
        }

    @Test
    fun twoSourcesAreNamedTogetherOnOneLineAndOneRetryAsksBothAgain() {
        val two = setOf(SourceId.LASTFM, SourceId.MUSICBRAINZ)
        show(state(sundownWith(two))) {
            assertEquals(1, countText(insightLine(both)))
            compose.onNodeWithTag(INSIGHT_RETRY_TAG).assert(labelled("ask $both again"))
            tap(INSIGHT_RETRY_TAG)
            tap(GENRES_CHIP_TAG)
            assertEquals(1, countText(genresLine(both)))
            assertEquals(1, count(RETRY_TAG))
            tap(RETRY_TAG)
            tap(SOURCES_CHIP_TAG)
            compose.onNodeWithTag(SOURCES_NOTE_TAG).assertTextEquals(text(R.string.home_source_note_unreachable, both))
            tap(SOURCES_RETRY_TAG)
            assertEquals(List(3) { Triple(key, two, sundown) }, retried)
        }
    }

    @Test
    fun threeSourcesAreListedWithCommasAndAndBeforeTheLastAndOneRetryAsksThemAll() {
        val three = setOf(SourceId.LASTFM, SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA)
        val listed = "Last.fm, MusicBrainz and Wikipedia"
        show(state(sundownWith(three))) {
            assertEquals(1, countText("$listed could not be reached; some evidence for the insight may be missing."))
            compose.onNodeWithTag(INSIGHT_RETRY_TAG).assert(labelled("ask $listed again"))
            tap(INSIGHT_RETRY_TAG)
            tap(SOURCES_CHIP_TAG)
            compose.onNodeWithTag(SOURCES_NOTE_TAG).assertTextEquals("$listed could not be reached.")
            tap(SOURCES_RETRY_TAG)
            assertEquals(List(2) { Triple(key, three, sundown) }, retried)
        }
    }

    @Test
    fun retryAsksOnlyTheSourcesThePipelineOffers() =
        show(state(sundownWith(setOf(SourceId.LASTFM, SourceId.MUSICBRAINZ)), offered = setOf(SourceId.LASTFM))) {
            tap(INSIGHT_RETRY_TAG)
            assertEquals(listOf(Triple(key, setOf(SourceId.LASTFM), sundown)), retried)
        }

    @Test
    fun withoutRetryOfferedTheLinesShowAlone() =
        show(state(sundownWith(setOf(SourceId.MUSICBRAINZ)), offered = emptySet())) {
            compose.onNodeWithText(insightLine(musicBrainz)).assertExists()
            assertEquals(0, count(INSIGHT_RETRY_TAG))
            tap(SOURCES_CHIP_TAG)
            assertEquals(0, count(SOURCES_RETRY_TAG))
        }

    @Test
    fun theLineUnderTheInsightShowsOnlyWithAnInsightAndASourceItDrawsOnNotReached() {
        val cases =
            listOf(
                // Every source answered.
                state(sundownWith(emptySet())),
                // No insight written yet.
                state(sundownWith(setOf(SourceId.MUSICBRAINZ), InsightState.Idle(canWrite = true))),
                // LRCLIB gives no evidence line: the insight does not draw on it.
                state(
                    sundownWith(emptySet()).let {
                        it.copy(
                            sources =
                                it.sources + (SourceId.LRCLIB to done(Outcome.Unreachable)),
                        )
                    },
                ),
            )
        for (case in cases) show(case) { assertEquals(0, count(INSIGHT_PARTIAL_TAG)) }
    }

    @Test
    fun onceTheSourcesAnswerTheLinesGo() {
        val two = setOf(SourceId.LASTFM, SourceId.MUSICBRAINZ)
        show(state(sundownWith(two))) {
            tap(GENRES_CHIP_TAG)
            tap(SOURCES_CHIP_TAG)
            assertEquals(1, count(INSIGHT_PARTIAL_TAG))
            assertEquals(1, countText(genresLine(both)))
            assertEquals(1, count(SOURCES_RETRY_TAG))
            tap(INSIGHT_RETRY_TAG)
            // The pipeline asks them again, and they answer.
            current.value = state(sundownWith(emptySet()))
            compose.waitForIdle()
            assertEquals(0, count(INSIGHT_PARTIAL_TAG))
            assertEquals(0, count(INSIGHT_RETRY_TAG))
            assertEquals(0, countText(genresLine(both)))
            assertEquals(0, count(RETRY_TAG))
            assertEquals(0, count(SOURCES_RETRY_TAG))
            assertEquals(0, count(SOURCES_NOTE_TAG))
            // The insight as it was (its line in *Sources* says it too).
            assertTrue(
                compose.onAllNodesWithText("It topped the charts", substring = true).fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    @Test
    fun datesAndLyricsSayWhatTheySaidBefore() =
        show(state(sundownWith(setOf(SourceId.MUSICBRAINZ)))) {
            val before = text(R.string.home_musicbrainz_unreachable)
            assertEquals("MusicBrainz could not be reached.", before)
            tap(DATES_CHIP_TAG)
            compose.onNodeWithText(before).assertExists()
            tap(DATES_PANEL_CLOSE_TAG)
            tap(LYRICS_CHIP_TAG)
            compose.onNodeWithText(before).assertExists()
        }

    @Test
    fun underSeparatePagesSourcesPageAndTheGenresPageSayItWithRetry() {
        val two = setOf(SourceId.LASTFM, SourceId.MUSICBRAINZ)
        val pages = Settings(layout = Layout.SEPARATE_PAGES)
        show(state(sundownWith(two), pages)) {
            assertEquals(1, countText(insightLine(both)))
        }
        show(state(sundownWith(two), pages), HomePage.Sources()) {
            compose.onNodeWithTag(SOURCES_NOTE_TAG).assertTextEquals(text(R.string.home_source_note_unreachable, both))
            tap(SOURCES_RETRY_TAG)
        }
        show(state(sundownWith(two), pages), HomePage.Genres) {
            assertEquals(1, countText(genresLine(both)))
            tap(RETRY_TAG)
        }
        assertEquals(List(2) { Triple(key, two, sundown) }, retried)
    }

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeRetryStaysBesideEachLineOnTheScreen() {
        val two = setOf(SourceId.LASTFM, SourceId.MUSICBRAINZ)
        show(state(sundownWith(two))) {
            val width =
                compose
                    .onRoot()
                    .fetchSemanticsNode()
                    .boundsInRoot.right
            compose.onNodeWithTag(INSIGHT_RETRY_TAG).performScrollTo()
            val message = compose.onNodeWithText(insightLine(both)).fetchSemanticsNode().boundsInRoot
            val retry = bounds(INSIGHT_RETRY_TAG)
            assertTrue(retry.left >= message.right)
            assertTrue(retry.right <= width)
            tap(GENRES_CHIP_TAG)
            compose.onNodeWithTag(RETRY_TAG).performScrollTo()
            assertTrue(bounds(RETRY_TAG).right <= width)
            tap(SOURCES_CHIP_TAG)
            compose.onNodeWithTag(SOURCES_RETRY_TAG).performScrollTo()
            assertTrue(bounds(SOURCES_RETRY_TAG).right <= width)
        }
    }
}
