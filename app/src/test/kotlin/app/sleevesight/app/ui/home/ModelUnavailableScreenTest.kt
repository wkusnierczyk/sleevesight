package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.pipeline.UnavailableModel
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.ui.home.HomeFixtures.normalizer
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.app.ui.home.HomeFixtures.written
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The insight card while the switched-on AI service's model is found unavailable (#540): it says
 * so, and asks for another model or service, with a link to the settings, and offers no Write.
 */
@RunWith(RobolectricTestRunner::class)
class ModelUnavailableScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private var settingsOpened = 0
    private val settingsOpenedAt = mutableListOf<SettingsTarget>()

    private val haiku = UnavailableModel(ProviderId.ANTHROPIC, "claude-haiku-5-5")

    private fun show(
        insight: InsightState,
        unavailable: UnavailableModel?,
        settings: Settings = Settings(aiProvider = ProviderId.ANTHROPIC),
        block: () -> Unit,
    ) {
        val state =
            NowPlayingState(
                PipelineView(track(insight = insight), aiReady = false, retry = true),
                sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = settings,
                unavailable = unavailable,
            )
        val actions =
            NowPlayingActions(
                normalizer::normalize,
                openSettings = { settingsOpened++ },
                openSettingsAt = { settingsOpenedAt += it },
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            block()
        }
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private val said =
        "The currently selected Anthropic model Claude Haiku 5.5 is not available. " +
            "Please select another model or provider in AI services."

    @Test
    fun anUnavailableModelIsSaidInPlaceOfWhyNothingIsWrittenWithALinkToItsRowInTheSettings() =
        show(InsightState.Idle(canWrite = false), haiku) {
            compose.onNodeWithTag(MODEL_UNAVAILABLE_TAG).performScrollTo().assertTextEquals(said)
            val place = app.getString(R.string.home_model_unavailable_place)
            assertEquals(said, app.getString(R.string.home_model_unavailable, "Anthropic", "Claude Haiku 5.5", place))
            compose.onNodeWithTag(MODEL_UNAVAILABLE_TAG).performFirstLinkClick()
            compose.waitForIdle()
            // The settings open at the service's model row, under *AI services* (#581).
            assertEquals(listOf(SettingsTarget(SettingsSection.AI_SERVICES, "model_ANTHROPIC")), settingsOpenedAt)
            assertEquals(0, settingsOpened)
        }

    @Test
    fun theCallItFailedSaysSoTooWithNoWriteToTryAgain() =
        show(InsightState.Failed("model_not_found"), haiku) {
            compose.onNodeWithTag(MODEL_UNAVAILABLE_TAG).performScrollTo().assertTextEquals(said)
            assertEquals(0, count(WRITE_TAG))
        }

    @Test
    fun aWrittenInsightStaysAndNothingIsSaidWithoutAnUnavailableModel() {
        show(written("It is a song [MB1]."), haiku) { assertEquals(0, count(MODEL_UNAVAILABLE_TAG)) }
        show(InsightState.Idle(canWrite = false), null) { assertEquals(0, count(MODEL_UNAVAILABLE_TAG)) }
        // Insights off: that is why nothing is written.
        show(InsightState.Idle(canWrite = false), haiku, Settings(writeInsights = false)) {
            assertEquals(0, count(MODEL_UNAVAILABLE_TAG))
        }
    }
}
