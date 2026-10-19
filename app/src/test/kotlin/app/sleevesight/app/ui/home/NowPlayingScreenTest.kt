package app.sleevesight.app.ui.home

import android.app.PendingIntent
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.CallId
import app.sleevesight.app.pipeline.PipelineHost
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.pipeline.RunId
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.LabelCredit
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackLabel
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The home screen's content in each state it shows, from published state only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NowPlayingScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(
        id: Int,
        vararg args: Any,
    ) = app.getString(id, *args)

    private var writes = 0
    private var tapped: Pair<RunKey, NowPlaying>? = null
    private val stops = mutableListOf<CallId>()
    private val sundownKey = RunKey.of(normalizer, sundown)

    private fun show(
        state: NowPlayingState,
        activity: () -> PendingIntent? = { null },
        block: (MainActivity) -> Unit,
    ) {
        val actions =
            NowPlayingActions(
                normalizer::normalize,
                { "Spotify" },
                activity,
                write = { key, shown ->
                    writes++
                    tapped = key to shown
                },
                stop = { stops += it },
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            scenario.onActivity { block(it) }
        }
    }

    private fun state(
        view: PipelineView,
        settings: Settings = Settings(),
    ) = NowPlayingState(view, sundown, PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING), settings = settings)

    @Test
    fun nothingPlayingShowsTheIdleLine() =
        show(NowPlayingState(PipelineView(track(insight = written("It is a song [MB1]."))), playing = null)) {
            compose.onNodeWithText(text(R.string.home_placeholder)).assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithTag(PLAYER_TAG).fetchSemanticsNodes().size)
        }

    @Test
    fun theTrackShowsWithItsPlayerAndKeyData() =
        show(state(PipelineView(track()))) {
            compose
                .onNodeWithText(
                    text(R.string.home_player_state, "Spotify", text(R.string.home_state_playing)),
                ).assertExists()
            // The title, and the album of the same name.
            assertEquals(2, compose.onAllNodesWithText("Sundown").fetchSemanticsNodes().size)
            compose.onNodeWithText("Gordon Lightfoot").assertExists()
        }

    @Test
    fun aNewTrackShowsAtOnceWithoutTheLastTracksInsight() =
        show(
            NowPlayingState(PipelineView(track(insight = written("It was recorded in 1973 [MB1]."))), carefree),
        ) {
            compose.onNodeWithText("Carefree Highway").assertExists()
            assertEquals(0, compose.onAllNodesWithText("It was recorded", substring = true).fetchSemanticsNodes().size)
            compose.onNodeWithText(text(R.string.home_evidence_looking)).assertExists()
        }

    @Test
    fun writeIsATapThePipelineDecides() {
        writes = 0
        val view =
            PipelineView(track(insight = InsightState.Idle(canWrite = true)), aiReady = true, writeKey = sundownKey)
        show(state(view)) {
            compose.onNodeWithTag(WRITE_TAG).performScrollTo().performClick()
            assertEquals(1, writes)
            // The tap carries the run it was offered for, and the track it was shown beside.
            assertEquals(sundownKey to sundown, tapped)
        }
    }

    @Test
    fun writeOfferedBecauseAutomaticWritingPausedSaysSo() {
        val offered = track(insight = InsightState.Idle(canWrite = true))
        show(state(PipelineView(offered, aiReady = true, writeKey = sundownKey, lapsed = true))) {
            compose.onNodeWithText(text(R.string.home_insight_paused)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.home_insight_tap_write)).assertDoesNotExist()
            compose.onNodeWithTag(WRITE_TAG).assertExists()
        }
        show(state(PipelineView(offered, aiReady = true, writeKey = sundownKey))) {
            compose.onNodeWithText(text(R.string.home_insight_paused)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.home_insight_tap_write)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun anIdleInsightSaysWhy() {
        val idle = track(insight = InsightState.Idle(canWrite = false))
        show(state(PipelineView(idle, aiReady = true), Settings(writeInsights = false))) {
            compose.onNodeWithText(text(R.string.home_insight_off)).assertExists()
        }
        show(state(PipelineView(idle, aiReady = false))) {
            compose.onNodeWithText(text(R.string.home_insight_unavailable)).assertExists()
            assertEquals(0, compose.onAllNodesWithTag(WRITE_TAG).fetchSemanticsNodes().size)
        }
        val gathering = idle.copy(sources = mapOf(SourceId.MUSICBRAINZ to SourceState.Pending), evidence = null)
        show(state(PipelineView(gathering, aiReady = true))) {
            compose.onNodeWithText(text(R.string.home_insight_gathering)).assertExists()
        }
    }

    @Test
    fun theInsightsOtherStatesSaySo() {
        show(state(PipelineView(track(insight = InsightState.NoEvidence), aiReady = true))) {
            compose.onNodeWithText(text(R.string.home_insight_no_evidence)).assertExists()
        }
        show(state(PipelineView(track(insight = InsightState.Failed("rate_limited")), aiReady = true))) {
            val reason = text(R.string.home_failure_rate_limited)
            compose.onNodeWithText(text(R.string.home_insight_failed, reason)).assertExists()
        }
        show(state(PipelineView(track(insight = InsightState.Writing("")), aiReady = true))) {
            compose.onNodeWithText(text(R.string.home_insight_writing)).assertExists()
        }
    }

    @Test
    fun aFailedInsightOffersWriteAgainOnlyWhileThePipelineDoes() {
        val failed = track(insight = InsightState.Failed("overloaded"))
        val reason = text(R.string.home_failure_overloaded)
        show(state(PipelineView(failed, aiReady = true))) {
            compose.onNodeWithText(text(R.string.home_insight_failed, reason)).assertExists()
            assertEquals(0, compose.onAllNodesWithTag(WRITE_TAG).fetchSemanticsNodes().size)
        }
        writes = 0
        show(state(PipelineView(failed, aiReady = true, retry = true, writeKey = sundownKey))) {
            compose.onNodeWithText(text(R.string.home_insight_failed, reason)).assertExists()
            compose.onNodeWithText(text(R.string.home_insight_retry)).assertExists()
            compose.onNodeWithTag(WRITE_TAG).performScrollTo().performClick()
            assertEquals(1, writes)
            assertEquals(sundownKey to sundown, tapped)
        }
    }

    @Test
    fun aStoppedInsightSaysSoAndOffersWriteAgain() {
        show(state(PipelineView(track(insight = InsightState.Stopped), aiReady = true))) {
            compose.onNodeWithText(text(R.string.home_insight_stopped)).assertExists()
            assertEquals(0, compose.onAllNodesWithTag(WRITE_TAG).fetchSemanticsNodes().size)
        }
        writes = 0
        val view =
            PipelineView(track(insight = InsightState.Stopped), aiReady = true, retry = true, writeKey = sundownKey)
        show(state(view)) {
            compose.onNodeWithText(text(R.string.home_insight_stopped)).assertExists()
            compose.onNodeWithTag(WRITE_TAG).performScrollTo().performClick()
            assertEquals(1, writes)
        }
    }

    @Test
    fun stopIsOfferedWhileTheCallIsUnderWayAndIsATapThePipelineDecides() {
        val writing = track(insight = InsightState.Writing("It was recorded"))
        show(state(PipelineView(writing, aiReady = true))) {
            assertEquals(0, compose.onAllNodesWithTag(STOP_TAG).fetchSemanticsNodes().size)
        }
        stops.clear()
        val call = CallId(RunId(1), 1)
        show(state(PipelineView(writing, aiReady = true, stopCall = call))) {
            compose.onNodeWithTag(STOP_TAG).performScrollTo().performClick()
            // The tap carries the call Stop was offered for.
            assertEquals(listOf(call), stops)
        }
    }

    @Test
    fun aRewriteStoppedShowsTheCardWrittenEarlierAndSaysSo() {
        val view =
            PipelineView(
                track(insight = written("It was recorded in 1973 [MB1].")),
                aiReady = true,
                rewrite = true,
                rewriteFailed = PipelineHost.STOPPED,
                writeKey = sundownKey,
            )
        show(state(view)) {
            compose.onNodeWithText(text(R.string.home_rewrite_stopped)).assertExists()
            compose.onNodeWithText("It was recorded in 1973", substring = true).assertExists()
            compose.onNodeWithTag(WRITE_TAG).assertExists()
        }
    }

    @Test
    fun aWrittenInsightCountsItsGroundingAndFlagsTheUncited() =
        show(
            state(
                PipelineView(track(insight = written("It was recorded in 1973 [MB1]. It is great.")), aiReady = true),
            ),
        ) {
            compose.onNodeWithText(text(R.string.home_meta_grounded, 1, 2), substring = true).assertExists()
            compose.onNodeWithText(text(R.string.home_meta_uncited, 1), substring = true).assertExists()
        }

    @Test
    fun aCitationShowsItsLineEvenWhenItsSourceIsHidden() =
        show(state(PipelineView(track(insight = written("It topped the charts [W1].")), aiReady = true))) {
            // Wikipedia's chip hides its lines (a display filter only).
            compose.onNodeWithTag(CHIP_PREFIX + "W").performScrollTo().performClick()
            assertEquals(0, compose.onAllNodesWithTag(LINE_PREFIX + "W1").fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodesWithTag(LINE_PREFIX + "WD1").fetchSemanticsNodes().size)
            compose.onNodeWithTag(LINE_PREFIX + "MB1").assertExists()

            compose.onNodeWithText("It topped the charts", substring = true).performScrollTo().performFirstLinkClick {
                (it.item as? LinkAnnotation.Clickable)?.tag == CITE_PREFIX + "W1"
            }
            compose.waitForIdle()
            compose.onNodeWithTag(LINE_PREFIX + "W1").assertIsDisplayed()
        }

    @Test
    fun aKeptCardsModelCitationStaysAsCheckedWhenTheSettingChanges() {
        // Written with the model's own knowledge allowed; the setting is off now (its default).
        val kept = track(insight = written("It is loved [M].", cached = true, allowModel = true))
        show(state(PipelineView(kept, aiReady = true))) {
            val texts =
                compose
                    .onNodeWithText(
                        "It is loved",
                        substring = true,
                    ).fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
            val prose = texts.first { "It is loved" in it.text }
            val chip = prose.spanStyles.single { prose.text.substring(it.start, it.end).trim() == "M" }
            assertEquals(lightColorScheme().tertiaryContainer, chip.item.background)
        }
    }

    @Test
    fun theKeptCardOffersRewriteAndSaysWhenARewriteFailed() {
        writes = 0
        val kept = track(insight = written("It was recorded in 1973 [MB1].", cached = true))
        show(state(PipelineView(kept, aiReady = true, rewrite = true, writeKey = sundownKey))) {
            compose.onNodeWithText(text(R.string.home_rewrite_offered)).assertExists()
            compose.onNodeWithText(text(R.string.home_evidence_cited)).assertExists()
            compose.onNodeWithTag(WRITE_TAG).performScrollTo().performClick()
            assertEquals(1, writes)
        }
        show(state(PipelineView(kept, aiReady = true, rewriteFailed = "offline"))) {
            val reason = text(R.string.home_failure_offline)
            compose.onNodeWithText(text(R.string.home_rewrite_failed, reason)).assertExists()
            assertEquals(0, compose.onAllNodesWithTag(WRITE_TAG).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun aFilterChipKeepsItsTextAndSaysWhetherItsLinesShow() =
        show(state(PipelineView(track()))) {
            val chip = compose.onNodeWithTag(CHIP_PREFIX + "MB").performScrollTo()
            chip.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
            chip.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    text(R.string.home_source_lines_shown),
                ),
            )
            chip.performClick()
            chip.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    text(R.string.home_source_lines_hidden),
                ),
            )
            chip.assertTouchHeightIsEqualTo(48.dp)
        }

    @Test
    fun theLinesTextsStartInOneColumn() =
        show(state(PipelineView(track()))) {
            val lines = listOf(HomeFixtures.musicBrainzLine, HomeFixtures.wikipediaLine, HomeFixtures.wikidataLine)
            // The tags differ in width (MB1, W1, WD1); the texts beside them start at one x.
            val starts =
                lines.map {
                    compose
                        .onNodeWithText(it.text)
                        .fetchSemanticsNode()
                        .positionInRoot.x
                }
            assertEquals(1, starts.distinct().size)
            // And no tag wraps: each is one line, so all are one height.
            val heights =
                listOf("MB1", "W1", "WD1").map {
                    compose
                        .onNodeWithText(it)
                        .fetchSemanticsNode()
                        .size.height
                }
            assertEquals("tag heights $heights", 1, heights.distinct().size)
        }

    @Test
    fun theSourcesSayWhatEachFoundNeedsOrRejected() {
        val state =
            track(
                sources =
                    mapOf(
                        SourceId.MUSICBRAINZ to done(evidence = listOf(HomeFixtures.musicBrainzLine), cached = true),
                        SourceId.WIKIPEDIA to done(Outcome.Rejected("a different Sundown")),
                    ),
            )
        show(state(PipelineView(state, needsKey = setOf(SourceId.LASTFM)))) {
            val kept = text(R.string.home_source_cached, text(R.string.home_source_found_song))
            compose.onNodeWithText(text(R.string.home_source_chip, "MB", kept)).assertExists()
            val needsKey = text(R.string.home_source_chip, "L", text(R.string.home_source_needs_key))
            compose.onNodeWithText(needsKey).assertExists()
            val wikipedia = text(R.string.settings_source_wikipedia)
            compose.onNodeWithText(text(R.string.home_rejected, wikipedia, "a different Sundown")).assertExists()
        }
    }

    @Test
    fun aGenreLabelShowsItsSourcesWithMusicBrainzsLicence() {
        val folk = GenreLabel("folk", "folk", 135, "Folk", "folk")
        val label =
            TrackLabel(
                folk,
                listOf(LabelCredit(LabelSource.MUSICBRAINZ, "https://musicbrainz.org/artist/1")),
            )
        show(state(PipelineView(track().copy(genres = listOf(label))))) {
            compose.onNodeWithText(text(R.string.home_genres)).assertExists()
            val chip = compose.onNodeWithTag(GENRE_PREFIX + "folk").performScrollTo()
            chip.assertIsNotSelected().performClick()
            chip.assertIsSelected()
            compose.onNodeWithText(LabelSource.MUSICBRAINZ.credit, substring = true).assertExists()
        }
    }

    @Test
    fun whileThePhoneIsOfflineTheSourcesSaySo() {
        val unreachable =
            track(sources = mapOf(SourceId.MUSICBRAINZ to done(), SourceId.WIKIPEDIA to done(Outcome.Unreachable)))
        show(state(PipelineView(unreachable, offline = true))) {
            compose.onNodeWithText(text(R.string.home_sources_offline)).performScrollTo().assertIsDisplayed()
        }
        show(state(PipelineView(unreachable, offline = false))) {
            compose.onNodeWithText(text(R.string.home_sources_offline)).assertDoesNotExist()
        }
    }

    @Test
    fun thePlayerRowOpensThePlayersScreen() {
        val opened = PendingIntent.getActivity(app, 0, Intent(Intent.ACTION_VIEW), PendingIntent.FLAG_IMMUTABLE)
        show(state(PipelineView(track())), activity = { opened }) {
            compose.onNodeWithTag(PLAYER_TAG).performClick()
            val started = shadowOf(it).nextStartedActivity ?: shadowOf(app).nextStartedActivity
            assertTrue(started != null)
        }
        show(state(PipelineView(track()))) {
            compose.onNodeWithTag(PLAYER_TAG).performClick()
            assertEquals(text(R.string.home_player_no_screen, "Spotify"), ShadowToast.getTextOfLatestToast())
        }
        assertFalse(openPlayerScreen(app, null))
    }
}
