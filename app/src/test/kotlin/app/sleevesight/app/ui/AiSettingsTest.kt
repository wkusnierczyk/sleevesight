package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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

/**
 * *AI service* on the settings screen (#227): per provider offered, a switch, its key, its model
 * and its reasoning effort, each named for the provider; a provider switched off keeps them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiSettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }

    /** Where the screen saves, as the process's scope in the app: it outlives the screen. */
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private fun text(id: Int) = app.getString(id)

    private fun absent(id: Int) = compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isEmpty()

    /** A string that names the provider, for Anthropic. */
    private fun anthropic(id: Int) = app.getString(id, text(R.string.settings_ai_provider_anthropic))

    /** A string that names the provider, for Google. */
    private fun google(id: Int) = app.getString(id, text(R.string.settings_ai_provider_google))

    /** The effort row titled [title] showing the level [level]: one row, whatever the others show. */
    private fun effortRow(
        title: String,
        level: Int,
    ) = hasText(title) and hasText(text(level))

    /** The text [id] in the open dialog, apart from the rows behind it. */
    private fun inDialog(id: Int) = hasText(text(id)) and hasAnyAncestor(isDialog())

    /** The model row's value, and the dialog's option, for Anthropic's default model. */
    private fun anthropicDefault() =
        app.getString(R.string.settings_ai_model_default, Settings.DEFAULT_MODELS.getValue(ProviderId.ANTHROPIC))

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
        secretStore: SecretStore = secrets,
        block: ActivityScenario<MainActivity>.() -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secretStore, providers, { CompletableDeferred(true) }, saves) { it }
                }
            }
            // The screen draws once the settings are read.
            waitForText(text(R.string.settings_title))
            scenario.block()
        }
    }

    /** The stored settings; a step that reads the screen after them waits for it too. */
    private fun current(): Settings = runBlocking { settings.settings.first() }

    /** Waits for [condition]; a timeout names [what]. */
    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) = compose.waitUntil(conditionDescription = what, timeoutMillis = 5_000) { condition() }

    private fun waitForNode(matcher: SemanticsMatcher) =
        waitFor("a node: ${matcher.description}") { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) = waitForNode(hasText(text))

    private fun ComposeTestRule.onAllNodesWithSubstring(text: String) =
        onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes()

    @Test
    fun theModelComesFromTheServicesList() {
        val provider = FakeProvider()
        show(providers = mapOf(ProviderId.ANTHROPIC to provider)) {
            // A provider's model can be chosen while it is switched off.
            assertEquals(null, current().aiProvider)
            compose.onNodeWithText(anthropicDefault()).performScrollTo().assertIsDisplayed()

            // Without a key, only the default. The dialog says so once it has looked for the key.
            compose.onNodeWithText(anthropic(R.string.settings_ai_model_of)).performScrollTo().performClick()
            waitForText(text(R.string.settings_model_no_key))
            compose.onNodeWithText(text(R.string.settings_model_no_key)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()

            runBlocking { secrets.set(SecretId.ANTHROPIC, Secret("sk-test")) }
            compose.onNodeWithText(anthropic(R.string.settings_ai_model_of)).performScrollTo().performClick()
            waitFor("the listed models") { compose.onAllNodesWithSubstring("Claude Opus 5.5").size == 1 }
            compose.onNodeWithText("Claude Opus 5.5 (claude-opus-5-5)").performClick()

            waitFor("the model stored") { current().aiModels[ProviderId.ANTHROPIC] == "claude-opus-5-5" }
            assertEquals("sk-test", provider.keyUsed)
        }
    }

    @Test
    fun withoutAClientTheDefaultModelIsOffered() =
        show {
            compose.onNodeWithText(anthropic(R.string.settings_ai_model_of)).performScrollTo().performClick()
            // The dialog shows *Loading* until it has looked for a key, on the key store's thread.
            waitForText(text(R.string.settings_model_unavailable))
            compose.onNodeWithText(text(R.string.settings_model_unavailable)).assertIsDisplayed()
            // In the row and as the dialog's one option.
            compose.onAllNodesWithText(anthropicDefault()).assertCountEquals(2)
        }

    @Test
    fun aProvidersSwitchTurnsItOnAndOffAndItKeepsItsModelAndEffort() {
        runBlocking {
            settings.update {
                it
                    .withModel(ProviderId.ANTHROPIC, "claude-opus-5-5")
                    .withEffort(ProviderId.ANTHROPIC, ReasoningEffort.HIGH)
            }
        }
        show {
            val anthropic = compose.onNodeWithText(text(R.string.settings_ai_provider_anthropic)).performScrollTo()
            anthropic.assertIsOff()
            // Off, its choices are shown and kept.
            compose.onNodeWithText("claude-opus-5-5").performScrollTo().assertIsDisplayed()
            compose
                .onNode(effortRow(anthropic(R.string.settings_ai_effort_of), R.string.settings_effort_high))
                .performScrollTo()
                .assertIsDisplayed()
            assertTrue(absent(R.string.settings_ai_provider_needs_key))

            anthropic.performClick()
            waitFor("Anthropic switched on") { current().aiProvider == ProviderId.ANTHROPIC }
            assertEquals("claude-opus-5-5", current().model)
            assertEquals(ReasoningEffort.HIGH, current().effort)
            // On with no key saved, it says that insights wait for one.
            waitForText(text(R.string.settings_ai_provider_needs_key))

            runBlocking { secrets.set(SecretId.ANTHROPIC, Secret("anthropic-key")) }
            waitFor("the key notice gone") { absent(R.string.settings_ai_provider_needs_key) }

            anthropic.performClick()
            waitFor("Anthropic switched off") { current().aiProvider == null }
            val off = current()
            assertEquals(null, off.model)
            assertEquals("claude-opus-5-5", off.model(ProviderId.ANTHROPIC))
            assertEquals(ReasoningEffort.HIGH, off.effort(ProviderId.ANTHROPIC))
        }
    }

    @Test
    fun aProviderSwitchedOnSaysNothingOfItsKeyBeforeTheKeysAreRead() {
        // A key file whose first read never ends: the keys' states are not known.
        val unread =
            object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow { awaitCancellation() }

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    awaitCancellation()
            }
        runBlocking { settings.update { it.copy(aiProvider = ProviderId.ANTHROPIC) } }
        show(secretStore = SecretStore(unread, testCipher())) {
            compose.onNodeWithText(text(R.string.settings_ai_provider_anthropic)).performScrollTo().assertIsOn()
            compose.waitForIdle()
            assertTrue(absent(R.string.settings_ai_provider_needs_key))
        }
    }

    @Test
    fun theModelAndEffortRowsAndDialogsNameTheirProvider() =
        show {
            compose.onNodeWithText(anthropic(R.string.settings_ai_model_of)).performScrollTo().performClick()
            // The row and the dialog's title.
            compose.onAllNodesWithText(anthropic(R.string.settings_ai_model_of)).assertCountEquals(2)
            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()

            compose.onNodeWithText(anthropic(R.string.settings_ai_effort_of)).performScrollTo().performClick()
            compose.onAllNodesWithText(anthropic(R.string.settings_ai_effort_of)).assertCountEquals(2)
        }

    @Test
    fun anEffortIsChosenFromTheProvidersOwnScale() =
        show {
            // Low, Anthropic's default, is its row's value.
            val row = anthropic(R.string.settings_ai_effort_of)
            compose.onNode(effortRow(row, R.string.settings_effort_low)).assertExists()
            compose.onNodeWithText(row).performScrollTo().performClick()
            // Anthropic's levels, without minimal, and the service's own default.
            val levels =
                listOf(R.string.settings_effort_low, R.string.settings_effort_medium, R.string.settings_effort_high)
            for (level in levels) {
                compose.onNode(inDialog(level)).assertIsDisplayed()
            }
            assertTrue(compose.onAllNodes(inDialog(R.string.settings_effort_minimal)).fetchSemanticsNodes().isEmpty())
            compose.onNode(inDialog(R.string.settings_effort_provider_default)).performClick()
            waitFor("the provider's default stored") {
                ProviderId.ANTHROPIC in current().aiEfforts && current().effort(ProviderId.ANTHROPIC) == null
            }
        }

    @Test
    fun googleHasItsOwnRowsAndItsOwnScale() =
        show {
            compose.onNodeWithText(text(R.string.settings_key_name_google)).performScrollTo().assertIsDisplayed()
            val default = app.getString(R.string.settings_ai_model_default, "gemini-3.5-flash-lite")
            compose.onNodeWithText(default).performScrollTo().assertIsDisplayed()

            compose.onNodeWithText(google(R.string.settings_ai_effort_of)).performScrollTo().performClick()
            compose.onAllNodesWithText(google(R.string.settings_ai_effort_of)).assertCountEquals(2)
            // Google's levels, minimal among them, which Anthropic's scale does not have.
            compose.onNode(inDialog(R.string.settings_effort_minimal)).performClick()
            waitFor("Google's minimal stored") { current().effort(ProviderId.GOOGLE) == ReasoningEffort.MINIMAL }
            assertEquals(ReasoningEffort.LOW, current().effort(ProviderId.ANTHROPIC))
        }

    @Test
    fun switchingGoogleOnSwitchesAnthropicOff() {
        runBlocking { settings.update { it.copy(aiProvider = ProviderId.ANTHROPIC) } }
        show {
            compose.onNodeWithText(text(R.string.settings_ai_provider_anthropic)).performScrollTo().assertIsOn()
            val google = compose.onNodeWithText(text(R.string.settings_ai_provider_google)).performScrollTo()
            google.assertIsOff()

            google.performClick()
            waitFor("Google switched on") { current().aiProvider == ProviderId.GOOGLE }
            compose.onNodeWithText(text(R.string.settings_ai_provider_anthropic)).performScrollTo().assertIsOff()
            assertEquals("gemini-3.5-flash-lite", current().model)
            assertEquals(ReasoningEffort.LOW, current().effort)
        }
    }
}
