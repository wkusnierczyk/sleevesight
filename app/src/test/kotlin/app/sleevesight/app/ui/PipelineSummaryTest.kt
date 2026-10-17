package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.synthesis.GroundedText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pipeline's state on the home screen's placeholder (#51): a key a source needs, and Write. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PipelineSummaryTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private val nowPlaying = NowPlaying(title = "Song", artists = "Artist", player = "com.spotify.music")

    private fun track(insight: InsightState) =
        TrackState(
            nowPlaying = nowPlaying,
            normalized = Normalizer(JvmAccentFolder).normalize(nowPlaying),
            sources = mapOf(SourceId.MUSICBRAINZ to SourceState.Pending),
            insight = insight,
        )

    private fun show(
        view: PipelineView,
        onWrite: () -> Unit = {},
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { PipelineSummary(view, onWrite) } }
            block()
        }
    }

    private fun stateText(
        source: Int,
        state: Int,
    ) = app.getString(R.string.home_source_state, app.getString(source), app.getString(state))

    @Test
    fun aSourceWithoutItsKeySaysSoAndTheTapWrites() {
        var taps = 0
        show(
            PipelineView(track(InsightState.Idle(canWrite = true)), needsKey = setOf(SourceId.LASTFM), aiReady = true),
            onWrite = { taps++ },
        ) {
            val needsKey = stateText(R.string.settings_source_lastfm, R.string.home_source_needs_key)
            compose.onNodeWithText(needsKey).assertExists()
            val looking = stateText(R.string.settings_source_musicbrainz, R.string.home_source_pending)
            compose.onNodeWithText(looking).assertExists()
            compose.onNodeWithTag(WRITE_TAG).performClick()
            assertEquals(1, taps)
        }
    }

    @Test
    fun aRewriteShowsTheInsightPaidForAndWrite() {
        var taps = 0
        val written = InsightState.Written(GroundedText("It is a song.", emptyList()), cached = true)
        show(PipelineView(track(written), aiReady = true, rewrite = true), onWrite = { taps++ }) {
            compose.onNodeWithText("It is a song.").assertExists()
            compose.onNodeWithTag(WRITE_TAG).performClick()
            assertEquals(1, taps)
        }
    }

    @Test
    fun withoutAnAiServiceThereIsNoWriteButButASetupHint() =
        show(PipelineView(track(InsightState.Idle(canWrite = false)), aiReady = false)) {
            assertEquals(0, compose.onAllNodesWithTag(WRITE_TAG).fetchSemanticsNodes().size)
            compose.onNodeWithText(app.getString(R.string.home_insight_unavailable)).assertExists()
        }
}
