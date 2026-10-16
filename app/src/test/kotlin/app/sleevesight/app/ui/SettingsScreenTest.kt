package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.FlakyDataStore
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.SoftwareKeySource
import app.sleevesight.app.TestStores
import app.sleevesight.app.secrets.SecretCipher
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
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
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.ProviderException
import javax.crypto.SecretKey
import kotlin.time.Duration.Companion.hours

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
        onOpenCredits: () -> Unit = {},
        settingsStore: SettingsStore = settings,
        clearHistory: suspend () -> Boolean = { true },
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settingsStore, secrets, providers, clearHistory, onOpenCredits) { "Label of $it" }
                }
            }
            // The screen draws once the settings are read.
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(text(R.string.settings_title)).fetchSemanticsNodes().isNotEmpty()
            }
            block()
        }
    }

    /**
     * The stored settings. The screen reads them by a collection of its own, which can lag the
     * store, and `waitForIdle` does not wait for DataStore's threads: after a wait on [current], a
     * step that reads the screen waits for the screen too ([waitForNode], [waitForText]).
     */
    private fun current(store: SettingsStore = settings): Settings = runBlocking { store.settings.first() }

    /** Waits for [condition]; a timeout names [what]. */
    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) = compose.waitUntil(conditionDescription = what, timeoutMillis = 5_000) { condition() }

    private fun waitForNode(matcher: SemanticsMatcher) =
        waitFor("a node: ${matcher.description}") { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) = waitForNode(hasText(text))

    /** The model row's value, and the dialog's option, for Anthropic's default model. */
    private fun anthropicDefault() =
        app.getString(R.string.settings_ai_model_default, Settings.DEFAULT_MODELS.getValue(ProviderId.ANTHROPIC))

    @Test
    fun aSwitchIsSavedAtOnce() =
        show {
            compose.onNodeWithText(text(R.string.settings_background)).performScrollTo().performClick()
            waitFor("look-up in the background stored on") { current().lookUpInBackground }

            compose.onNodeWithText("Label of ${Settings.SPOTIFY}").performScrollTo().performClick()
            waitFor("Spotify stored off") { current().followedPlayers[Settings.SPOTIFY] == false }
        }

    @Test
    fun aSwitchThatCannotBeSavedKeepsItsStoredValueAndSaysSo() {
        val flaky = FlakyDataStore(stores.open("flaky-settings"))
        val store = SettingsStore(flaky)
        show(settingsStore = store) {
            flaky.writeFailures.set(1)
            val row = compose.onNodeWithText(text(R.string.settings_background)).performScrollTo()
            row.performClick()

            waitForText(text(R.string.settings_change_failed))
            row.assertIsOff()
            assertEquals(false, current(store).lookUpInBackground)
            // The error's own text is not shown.
            assertEquals(0, compose.onAllNodesWithSubstring("cannot write").size)

            // The next change is saved, and the message goes.
            row.performClick()
            waitFor("look-up in the background stored on") { current(store).lookUpInBackground }
            waitForNode(hasText(text(R.string.settings_background)) and isOn())
            waitFor("the failure message gone") {
                compose.onAllNodesWithText(text(R.string.settings_change_failed)).fetchSemanticsNodes().isEmpty()
            }
        }
    }

    @Test
    fun aChoiceThatCannotBeSavedKeepsItsStoredValueAndSaysSo() {
        val flaky = FlakyDataStore(stores.open("flaky-settings"))
        val store = SettingsStore(flaky)
        show(settingsStore = store) {
            flaky.writeFailures.set(1)
            compose.onNodeWithText(text(R.string.settings_ai_effort)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_effort_high)).performClick()

            waitForText(text(R.string.settings_change_failed))
            // The dialog has closed, and the row still shows the stored effort.
            assertEquals(0, compose.onAllNodesWithText(text(R.string.settings_effort_high)).fetchSemanticsNodes().size)
            compose.onNodeWithText(text(R.string.settings_effort_low)).performScrollTo().assertIsDisplayed()
            assertEquals(ReasoningEffort.LOW, current(store).aiEffort)
        }
    }

    @Test
    fun aSourceKeyIsSavedSealedAndNeverShown() =
        show {
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextInput("  lastfm-key  ")
            compose.onNodeWithText(text(R.string.settings_save)).performClick()

            waitFor("the Last.fm key stored") { runBlocking { secrets.get(SecretId.LASTFM) }?.reveal() == "lastfm-key" }
            waitForText(text(R.string.settings_key_saved))
            compose.onNodeWithText(text(R.string.settings_key_saved)).performScrollTo().assertIsDisplayed()
            assertEquals(0, compose.onAllNodesWithSubstring("lastfm-key").size)
        }

    @Test
    fun aKeyThatCannotBeSavedKeepsTheDialogOpenAndSaysSo() {
        val failing =
            object : SoftwareKeySource() {
                override fun find(): SecretKey? = throw ProviderException("key store down")
            }
        val failingSecrets = SecretStore(stores.open("failing-secrets"), SecretCipher(failing))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { SettingsScreen(settings, failingSecrets, emptyMap(), { true }) { it } }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(text(R.string.settings_title)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(text(R.string.settings_key_name_exa)).performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextInput("exa-key")
            compose.onNodeWithText(text(R.string.settings_save)).performClick()

            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(text(R.string.settings_key_save_failed)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(text(R.string.settings_save)).assertIsDisplayed()
            assertEquals(null, runBlocking { failingSecrets.get(SecretId.EXA) })
        }
    }

    @Test
    fun anUnreadableKeyOffersResetSavedKeys() {
        val dataStore = stores.open("reset-secrets")
        runBlocking { SecretStore(dataStore, testCipher()).set(SecretId.LASTFM, Secret("lastfm-key")) }
        // Another key than the one that sealed it, as after the Keystore lost the first.
        val resetting = SecretStore(dataStore, testCipher())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { SettingsScreen(settings, resetting, emptyMap(), { true }) { it } }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(text(R.string.settings_reset_keys)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(text(R.string.settings_key_unreadable)).performScrollTo().assertIsDisplayed()

            compose.onNodeWithText(text(R.string.settings_reset_keys)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_reset_keys_explained)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.settings_reset)).performClick()

            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(text(R.string.settings_reset_keys)).fetchSemanticsNodes().isEmpty()
            }
            assertEquals(SecretState.ABSENT, runBlocking { resetting.states.first() }.getValue(SecretId.LASTFM))
        }
    }

    @Test
    fun theModelComesFromTheServicesList() {
        val provider = FakeProvider()
        show(providers = mapOf(ProviderId.ANTHROPIC to provider)) {
            compose.onNodeWithText(text(R.string.settings_ai_model)).assertIsNotEnabled()

            compose.onNodeWithText(text(R.string.settings_ai_provider)).performClick()
            compose.onNodeWithText(text(R.string.settings_ai_provider_anthropic)).performClick()
            waitFor("Anthropic stored") { current().aiProvider == ProviderId.ANTHROPIC }
            // The model row opens its dialog only once the screen has the service.
            waitForText(anthropicDefault())

            // Without a key, only the default. The dialog says so once it has looked for the key.
            compose.onNodeWithText(text(R.string.settings_ai_model)).performClick()
            waitForText(text(R.string.settings_model_no_key))
            compose.onNodeWithText(text(R.string.settings_model_no_key)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()

            runBlocking { secrets.set(SecretId.ANTHROPIC, Secret("sk-test")) }
            compose.onNodeWithText(text(R.string.settings_ai_model)).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithSubstring("Claude Opus 5.5").size == 1 }
            compose.onNodeWithText("Claude Opus 5.5 (claude-opus-5-5)").performClick()

            waitFor("the model stored") { current().aiModel == "claude-opus-5-5" }
            assertEquals("sk-test", provider.keyUsed)
        }
    }

    @Test
    fun withoutAClientTheDefaultModelIsOffered() =
        show {
            compose.onNodeWithText(text(R.string.settings_ai_provider)).performClick()
            compose.onNodeWithText(text(R.string.settings_ai_provider_anthropic)).performClick()
            waitFor("Anthropic stored") { current().aiProvider == ProviderId.ANTHROPIC }
            // The model row opens its dialog only once the screen has the service.
            waitForText(anthropicDefault())

            compose.onNodeWithText(text(R.string.settings_ai_model)).performClick()
            // The dialog shows *Loading* until it has looked for a key, on the key store's thread.
            waitForText(text(R.string.settings_model_unavailable))
            compose.onNodeWithText(text(R.string.settings_model_unavailable)).assertIsDisplayed()
            // In the row and as the dialog's one option.
            compose.onAllNodesWithText(anthropicDefault()).assertCountEquals(2)
        }

    @Test
    fun aCacheLifetimeIsChosen() =
        show {
            // Web search under *Where to look*, then its cache lifetime.
            compose.onAllNodesWithText(text(R.string.settings_cache_web))[1].performScrollTo().performClick()
            compose.onNodeWithText(app.resources.getQuantityString(R.plurals.settings_days, 3, 3)).performClick()
            waitFor("the web cache lifetime stored") { current().cacheLifetimes[SourceId.WEB]?.inWholeDays == 3L }
        }

    @Test
    fun theHistorysLifetimeAndSessionGapAreChosen() =
        show {
            compose.onNodeWithText(text(R.string.settings_keep_history)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_keep_history_forever)).performClick()
            waitFor { current().historyLifetime == null }

            compose.onNodeWithText(text(R.string.settings_session_gap)).performScrollTo().performClick()
            compose.onNodeWithText(app.resources.getQuantityString(R.plurals.settings_hours, 1, 1)).performClick()
            waitFor { current().sessionGap == 1.hours }
        }

    @Test
    fun clearHistoryAsksFirst() {
        var cleared = 0
        val clear: suspend () -> Boolean = {
            cleared++
            true
        }
        show(clearHistory = clear) {
            compose.onNodeWithText(text(R.string.settings_clear_history)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_clear_history_explained)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()
            compose.waitForIdle()
            assertEquals(0, cleared)

            compose.onNodeWithText(text(R.string.settings_clear_history)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_clear)).performClick()
            waitFor { cleared == 1 }
            assertEquals(
                0,
                compose.onAllNodesWithText(text(R.string.settings_clear_history_failed)).fetchSemanticsNodes().size,
            )
        }
    }

    @Test
    fun aFailedClearSaysSoUntilOneSucceeds() {
        var succeeds = false
        show(clearHistory = { succeeds }) {
            compose.onNodeWithText(text(R.string.settings_clear_history)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_clear)).performClick()
            waitForText(text(R.string.settings_clear_history_failed))

            succeeds = true
            compose.onNodeWithText(text(R.string.settings_clear_history)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_clear)).performClick()
            waitFor {
                compose.onAllNodesWithText(text(R.string.settings_clear_history_failed)).fetchSemanticsNodes().isEmpty()
            }
        }
    }

    @Test
    fun theMusicBrainzLabelsAreASwitchOnByDefault() =
        show {
            val row = compose.onNodeWithText(text(R.string.settings_labels_musicbrainz_summary)).performScrollTo()
            row.assertIsOn()
            row.performClick()
            waitFor("only Last.fm labels stored") { current().labelSources == setOf(LabelSource.LASTFM) }
            waitForNode(hasText(text(R.string.settings_labels_musicbrainz_summary)) and isOff())
        }

    @Test
    fun lastFmsLabelsWaitForItsKey() =
        show {
            // Without the key, the switch shows off, cannot move, and says why.
            val waiting = compose.onNodeWithText(text(R.string.settings_labels_needs_key)).performScrollTo()
            waiting.assertIsOff()
            waiting.assertIsNotEnabled()

            runBlocking { secrets.set(SecretId.LASTFM, Secret("lastfm-key")) }
            waitFor("the Last.fm labels no longer waiting for the key") {
                compose
                    .onAllNodesWithText(
                        text(R.string.settings_labels_needs_key),
                    ).fetchSemanticsNodes()
                    .isEmpty()
            }
            // Last.fm under *Where to look*, under *Genre labels*, and its cache lifetime.
            val label = compose.onAllNodesWithText(text(R.string.settings_source_lastfm))[1].performScrollTo()
            label.assertIsOn()
            label.assertIsEnabled()
            label.performClick()
            waitFor("only MusicBrainz labels stored") { current().labelSources == setOf(LabelSource.MUSICBRAINZ) }
        }

    @Test
    fun aLabelSourceOffUnderWhereToLookCannotBeSwitched() =
        show {
            runBlocking { settings.update { it.copy(enabledSources = it.enabledSources - SourceSwitch.MUSICBRAINZ) } }
            waitFor("the MusicBrainz labels shown off under *Where to look*") {
                compose
                    .onAllNodesWithText(
                        text(R.string.settings_labels_source_off),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText(text(R.string.settings_labels_source_off)).performScrollTo().assertIsNotEnabled()
            // The choice itself is kept for when MusicBrainz is back on.
            assertEquals(Settings.DEFAULT_LABEL_SOURCES, current().labelSources)
        }

    @Test
    fun aWebSearchEngineIsASwitchUnderWebSearch() =
        show {
            val exa = compose.onNodeWithText(text(R.string.settings_engine_exa)).performScrollTo()
            exa.assertIsOn()
            exa.performClick()
            waitFor("no web engine stored") { current().webEngines.isEmpty() }

            compose.onAllNodesWithText(text(R.string.settings_source_web))[0].performScrollTo().performClick()
            waitFor("web search stored off") { SourceSwitch.WEB !in current().enabledSources }
            waitForNode(hasText(text(R.string.settings_engine_exa)) and isNotEnabled())
            assertEquals(emptySet<WebEngine>(), current().webEngines)
        }

    @Test
    fun theCreditsAreAtTheEnd() {
        var opened = false
        show(onOpenCredits = { opened = true }) {
            compose.onNodeWithText(text(R.string.credits_title)).performScrollTo().performClick()
            assertTrue(opened)
        }
    }

    @Test
    fun theCreditsListMusicBrainzWithBothLicences() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { CreditsScreen() } }
            compose.onNodeWithText(text(R.string.credits_musicbrainz_tags)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("CC BY-NC-SA 3.0").performScrollTo().assertIsDisplayed()
            assertEquals(2, compose.onAllNodesWithText("CC0").fetchSemanticsNodes().size)
            for (credit in CREDITS) compose.onNodeWithText(text(credit.name)).performScrollTo().assertIsDisplayed()
        }
    }

    private fun ComposeTestRule.onAllNodesWithSubstring(text: String) =
        onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
}
