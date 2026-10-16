package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.ModelInfo
import app.sleevesight.core.ai.ModelList
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsScreenTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }

    @After
    fun close() = stores.close()

    private fun text(id: Int) = app.getString(id)

    /** An Anthropic client that lists two models for any key, and records the key it was given. */
    private class FakeProvider : AiProvider {
        var keyUsed: String? = null

        override val id = ProviderId.ANTHROPIC

        override suspend fun models(key: Secret): ModelList {
            keyUsed = key.reveal()
            return ModelList.Listed(
                listOf(
                    ModelInfo("claude-opus-5-5", "Claude Opus 5.5", null),
                    ModelInfo("claude-haiku-5-5", "Claude Haiku 5.5", null),
                ),
            )
        }

        override fun stream(
            request: CompletionRequest,
            key: Secret,
        ): Flow<CompletionEvent> = emptyFlow()
    }

    private fun show(
        providers: Map<ProviderId, AiProvider> = emptyMap(),
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { SettingsScreen(settings, secrets, providers) { "Label of $it" } }
            }
            // The screen draws once the settings are read.
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(text(R.string.settings_title)).fetchSemanticsNodes().isNotEmpty()
            }
            block()
        }
    }

    private fun current(): Settings = runBlocking { settings.settings.first() }

    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 5_000) { condition() }

    @Test
    fun aSwitchIsSavedAtOnce() =
        show {
            compose.onNodeWithText(text(R.string.settings_background)).performScrollTo().performClick()
            waitFor { current().lookUpInBackground }

            compose.onNodeWithText("Label of ${Settings.SPOTIFY}").performScrollTo().performClick()
            waitFor { current().followedPlayers[Settings.SPOTIFY] == false }
        }

    @Test
    fun aSourceKeyIsSavedSealedAndNeverShown() =
        show {
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextInput("  lastfm-key  ")
            compose.onNodeWithText(text(R.string.settings_save)).performClick()

            waitFor { runBlocking { secrets.get(SecretId.LASTFM) }?.reveal() == "lastfm-key" }
            compose.onNodeWithText(text(R.string.settings_key_saved)).performScrollTo().assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithSubstring("lastfm-key").size)
        }

    @Test
    fun theModelComesFromTheServicesList() {
        val provider = FakeProvider()
        show(providers = mapOf(ProviderId.ANTHROPIC to provider)) {
            compose.onNodeWithText(text(R.string.settings_ai_model)).assertIsNotEnabled()

            compose.onNodeWithText(text(R.string.settings_ai_provider)).performClick()
            compose.onNodeWithText(text(R.string.settings_ai_provider_anthropic)).performClick()
            waitFor { current().aiProvider == ProviderId.ANTHROPIC }

            // Without a key, only the default.
            compose.onNodeWithText(text(R.string.settings_ai_model)).performClick()
            compose.onNodeWithText(text(R.string.settings_model_no_key)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()

            runBlocking { secrets.set(SecretId.ANTHROPIC, Secret("sk-test")) }
            compose.onNodeWithText(text(R.string.settings_ai_model)).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithSubstring("Claude Opus 5.5").size == 1 }
            compose.onNodeWithText("Claude Opus 5.5 (claude-opus-5-5)").performClick()

            waitFor { current().aiModel == "claude-opus-5-5" }
            assertEquals("sk-test", provider.keyUsed)
        }
    }

    @Test
    fun withoutAClientTheDefaultModelIsOffered() =
        show {
            compose.onNodeWithText(text(R.string.settings_ai_provider)).performClick()
            compose.onNodeWithText(text(R.string.settings_ai_provider_anthropic)).performClick()
            waitFor { current().aiProvider == ProviderId.ANTHROPIC }

            compose.onNodeWithText(text(R.string.settings_ai_model)).performClick()
            compose.onNodeWithText(text(R.string.settings_model_unavailable)).assertIsDisplayed()
            // In the row and as the dialog's one option.
            compose
                .onAllNodesWithText(
                    app.getString(R.string.settings_ai_model_default, "claude-haiku-5-5"),
                ).assertCountEquals(2)
        }

    @Test
    fun aCacheLifetimeIsChosen() =
        show {
            compose.onNodeWithText(text(R.string.settings_cache_web)).performScrollTo().performClick()
            compose.onNodeWithText(app.resources.getQuantityString(R.plurals.settings_days, 3, 3)).performClick()
            waitFor { current().cacheLifetimes[SourceId.WEB]?.inWholeDays == 3L }
        }

    private fun ComposeTestRule.onAllNodesWithSubstring(text: String) =
        onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
}
