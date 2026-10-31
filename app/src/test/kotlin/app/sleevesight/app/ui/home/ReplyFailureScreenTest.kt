package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.ReplyFailures
import app.sleevesight.core.pipeline.RunKey
import app.sleevesight.core.settings.Settings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A reply that broke off, or ended with no text, says on the insight card what happened, each way in
 * its own words, and that Write insight tries again (#665).
 */
@RunWith(RobolectricTestRunner::class)
class ReplyFailureScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val sundownKey = RunKey.of(normalizer, sundown)

    private fun show(
        reason: String,
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(
                    track(insight = InsightState.Failed(reason)),
                    aiReady = true,
                    retry = true,
                    writeKey = sundownKey,
                ),
                sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(aiProvider = ProviderId.ANTHROPIC),
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NowPlayingContent(state, NowPlayingActions(normalizer::normalize))
                    }
                }
            }
            block()
        }
    }

    private val words =
        mapOf(
            ReplyFailures.INCOMPLETE to "the AI service’s reply broke off before it finished",
            ReplyFailures.CONNECTION_LOST to "the connection was lost before the AI service’s reply finished",
            ReplyFailures.TIMED_OUT to "the AI service’s reply took too long, so the app stopped waiting",
            ReplyFailures.LENGTH_LIMIT to "the AI model reached its length limit before it wrote anything",
            ReplyFailures.EMPTY to "the AI model’s reply was empty",
        )

    @Test
    fun aReplyThatBrokeOffSaysSoInTheIssuesWordsWithWriteInsightToTryAgain() =
        show(ReplyFailures.INCOMPLETE) {
            val line =
                "Writing failed: the AI service’s reply broke off before it finished. " +
                    "Tap Write insight to try again, as a new call."
            compose.onNodeWithText(line).performScrollTo()
        }

    @Test
    fun eachWayAReplyFailsHasItsOwnWords() {
        for ((reason, said) in words) {
            show(reason) {
                compose.onNodeWithText(app.getString(R.string.home_insight_failed_retry, said)).performScrollTo()
            }
        }
    }
}
