package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.ItemShown
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Key insights* for a track whose own sources gave nothing to narrate (#1131): while the song's, the
 * artist's or the album's insight is being written, whose searches may find lines it is then decided
 * again with, it waits rather than say the sources give nothing; once none is, it says so.
 */
@RunWith(RobolectricTestRunner::class)
class KeyInsightWaitsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun text(id: Int) = app.getString(id)

    private val notes = FakeItemNotes()

    @Test
    fun keyInsightsWaitsWhileAnItemIsWrittenAndThenSaysTheSourcesGiveNothing() {
        val track = track(insight = InsightState.NoEvidence)
        val state =
            NowPlayingState(
                PipelineView(track, aiReady = true),
                track.nowPlaying,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(writeAutomatically = WriteAutomatically.NEVER),
            )
        notes.shown.value = mapOf(InsightItem.ARTIST to ItemShown.Asking(ITEM_KEY))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(state, NowPlayingActions(normalizer::normalize, itemNotes = notes))
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithText(text(R.string.home_insight_no_evidence)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.home_insight_preparing)).assertExists()

            // The artist's write ended with nothing found: nothing more is coming.
            notes.shown.value = emptyMap()
            compose.waitForIdle()
            compose.onNodeWithText(text(R.string.home_insight_no_evidence)).assertExists()
        }
    }
}
