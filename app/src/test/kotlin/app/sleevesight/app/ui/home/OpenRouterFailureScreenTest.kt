package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performFirstLinkClick
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
 * Home, when OpenRouter refuses later (#1157): a write that failed for the key refused, or for no
 * credit, says what to do, with a link; with another service on, the general line as before.
 */
@RunWith(RobolectricTestRunner::class)
class OpenRouterFailureScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val settingsOpenedAt = mutableListOf<SettingsTarget>()

    private fun show(
        reason: String,
        provider: ProviderId,
        block: () -> Unit,
    ) {
        val view = PipelineView(track(insight = InsightState.Failed(reason)), aiReady = true, retry = true)
        val state =
            NowPlayingState(
                view,
                sundown,
                PlaybackInfo(Settings.SPOTIFY, PlaybackStatus.PLAYING),
                settings = Settings(aiProvider = provider),
            )
        val actions = NowPlayingActions(normalizer::normalize, openSettingsAt = { settingsOpenedAt += it })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.setContent {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NowPlayingContent(state, actions) }
                }
            }
            block()
        }
    }

    private fun line(
        id: Int,
        link: Int,
    ) = app.getString(id, app.getString(link))

    @Test
    fun aKeyRefusedSaysToSetANewKeyWithALinkToAiServices() =
        show("unauthorized", ProviderId.OPENROUTER) {
            compose
                .onNodeWithTag(OPENROUTER_FAILURE_TAG)
                .performScrollTo()
                .assertTextEquals(line(R.string.home_openrouter_unauthorized, R.string.home_settings_ai_services_link))
            assertEquals(
                "Writing failed: OpenRouter did not accept the key; it may have been deleted there. " +
                    "Set a new key in Settings → AI services.",
                line(R.string.home_openrouter_unauthorized, R.string.home_settings_ai_services_link),
            )
            compose.onNodeWithTag(OPENROUTER_FAILURE_TAG).performFirstLinkClick()
            compose.waitForIdle()
            assertEquals(listOf(SettingsTarget.aiServices), settingsOpenedAt)
        }

    @Test
    fun noCreditSaysToAddItWithALinkToOpenRouter() =
        show("billing", ProviderId.OPENROUTER) {
            compose
                .onNodeWithTag(OPENROUTER_FAILURE_TAG)
                .performScrollTo()
                .assertTextEquals(
                    "Writing failed: your OpenRouter account needs credit, or the key has reached its spending " +
                        "limit. Add credit on OpenRouter ↗",
                )
        }

    @Test
    fun anotherServiceKeepsTheGeneralLine() =
        show("unauthorized", ProviderId.ANTHROPIC) {
            compose.onAllNodesWithTag(OPENROUTER_FAILURE_TAG).assertCountEquals(0)
            compose
                .onAllNodesWithText(app.getString(R.string.home_failure_unauthorized), substring = true)
                .assertCountEquals(1)
        }

    @Test
    fun anotherFailureWithOpenRouterKeepsTheGeneralLine() =
        show("rate_limited", ProviderId.OPENROUTER) {
            compose.onAllNodesWithTag(OPENROUTER_FAILURE_TAG).assertCountEquals(0)
            compose
                .onAllNodesWithText(app.getString(R.string.home_failure_rate_limited), substring = true)
                .assertCountEquals(1)
        }
}
