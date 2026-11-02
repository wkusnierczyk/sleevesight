package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import app.sleevesight.app.TestStores
import app.sleevesight.app.idleUntil
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SEARCH_FIELD_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.Secret
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.WebEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The key dialog (#763): under the field, whether the key is new, and what saving it switches on;
 * then the summary.
 */
@RunWith(RobolectricTestRunner::class)
class KeyDialogEnableTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }

    // Over stores whose writes a test can fail or hold back.
    private val settingsData by lazy { FlakyDataStore(stores.open("settings")) }
    private val secretsData by lazy { FlakyDataStore(stores.open("secrets")) }
    private val settings by lazy { SettingsStore(settingsData) }
    private val secrets by lazy { SecretStore(secretsData, testCipher()) }
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun nothingKeyedOn() {
        runBlocking {
            settings.update {
                it.copy(
                    aiProvider = null,
                    enabledSources = it.enabledSources - SourceSwitch.WEB,
                    webEngines = emptySet(),
                )
            }
        }
    }

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private fun text(id: Int) = app.getString(id)

    private fun current(): Settings = runBlocking { settings.settings.first() }

    private fun waitForText(text: String) = waitForNode(hasText(text))

    private fun waitForNode(matcher: SemanticsMatcher) =
        idleUntil("a node: ${matcher.description}") { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    /** Waits for the settings the save applies, written in the background, to satisfy [condition]. */
    private fun settled(condition: () -> Boolean) =
        try {
            idleUntil("the settings written") { condition() }
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}; the settings: ${current()}", e)
        }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Opens the key dialog of [key] (its name, [name]) and types a key into it. */
    private fun open(
        name: Int,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        saves,
                    ) { it }
                }
            }
            waitForText(text(R.string.settings_title))
            compose.openSection(SettingsSection.SECRETS)
            compose.onNodeWithText(text(name)).performScrollTo().performClick()
            compose.onNode(keyField).performTextInput("typed-key-a1B2")
            block()
        }
    }

    private fun save() {
        compose.onNodeWithText(text(R.string.settings_save)).performClick()
        waitForText(text(R.string.load_keys_saved_title_one))
    }

    private fun state(
        service: Int,
        state: Int,
    ) = "${text(service)}, ${text(state)}"

    private fun with(
        id: Int,
        arg: Int,
    ) = app.getString(id, text(arg))

    @Test
    fun aNewAiKeyWithNoAiServiceOnOffersEnableTickedAndTheSummaryFollows() =
        open(R.string.settings_key_name_openai) {
            compose.onNodeWithText(text(R.string.key_save_as_new)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(with(R.string.key_enable, R.string.settings_ai_provider_openai)).assertIsOn()
            save()
            settled { current().aiProvider == ProviderId.OPENAI }
            compose
                .onNodeWithText(
                    "${text(R.string.settings_ai_provider_openai)}, ending a1B2, ${text(R.string.key_saved_new)}",
                ).performScrollTo()
                .assertIsDisplayed()
            compose
                .onNodeWithText(
                    state(R.string.settings_ai_provider_openai, R.string.key_service_enabled),
                ).performScrollTo()
                .assertIsDisplayed()

            compose.onNodeWithText(text(R.string.key_screen_done)).performClick()
            idleUntil("the summary closed") { !shown(text(R.string.load_keys_saved_title_one)) }
            assertEquals("typed-key-a1B2", runBlocking { secrets.get(SecretId.OPENAI) }?.reveal())
        }

    @Test
    fun anAiKeyWhileAnotherIsOnOffersTheSwitchUnticked() {
        runBlocking {
            secrets.set(SecretId.ANTHROPIC, Secret("anthropic-key"))
            settings.update { it.copy(aiProvider = ProviderId.ANTHROPIC) }
        }
        open(R.string.settings_key_name_openai) {
            compose
                .onNodeWithText(with(R.string.key_current_ai, R.string.settings_ai_provider_anthropic))
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText(with(R.string.key_switch_ai, R.string.settings_ai_provider_openai)).assertIsOff()
            save()
            // Unticked: the AI service stays, and only the key saved is listed.
            settled { current().aiProvider == ProviderId.ANTHROPIC }
            compose
                .onNodeWithText(
                    state(R.string.settings_ai_provider_openai, R.string.key_service_disabled),
                ).performScrollTo()
                .assertIsDisplayed()
            assertTrue(!shown(state(R.string.settings_ai_provider_anthropic, R.string.key_service_enabled)))
        }
    }

    @Test
    fun theSwitchTickedReplacesTheAiServiceAndTheSummarySaysBoth() {
        runBlocking {
            secrets.set(SecretId.ANTHROPIC, Secret("anthropic-key"))
            settings.update { it.copy(aiProvider = ProviderId.ANTHROPIC) }
        }
        open(R.string.settings_key_name_openai) {
            val switch = compose.onNodeWithText(with(R.string.key_switch_ai, R.string.settings_ai_provider_openai))
            switch.performScrollTo().performClick()
            switch.assertIsOn()
            save()
            compose
                .onNodeWithText(
                    state(R.string.settings_ai_provider_openai, R.string.key_service_enabled),
                ).assertExists()
            settled { current().aiProvider == ProviderId.OPENAI }
            compose
                .onNodeWithText(
                    state(R.string.settings_ai_provider_openai, R.string.key_service_enabled),
                ).performScrollTo()
                .assertIsDisplayed()
            compose
                .onNodeWithText(state(R.string.settings_ai_provider_anthropic, R.string.key_service_disabled))
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun replacingTheKeyOfTheAiServiceOnSaysItIsAlreadyEnabled() {
        runBlocking {
            secrets.set(SecretId.ANTHROPIC, Secret("anthropic-key"))
            settings.update { it.copy(aiProvider = ProviderId.ANTHROPIC) }
        }
        open(R.string.settings_key_name_anthropic) {
            compose.onNodeWithText(text(R.string.key_replace_existing)).performScrollTo().assertIsDisplayed()
            compose
                .onNodeWithText(
                    with(R.string.key_already_enabled, R.string.settings_ai_provider_anthropic),
                ).performScrollTo()
                .assertIsDisplayed()
            assertTrue(!shown(with(R.string.key_enable, R.string.settings_ai_provider_anthropic)))
            save()
            settled { current().aiProvider == ProviderId.ANTHROPIC }
            compose
                .onNodeWithText(
                    "${text(
                        R.string.settings_ai_provider_anthropic,
                    )}, ending a1B2, ${text(R.string.key_saved_replaced)}",
                ).performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun aSourceKeyUntickedIsSavedWithItsServiceLeftOff() =
        open(R.string.settings_key_name_exa) {
            val enable = compose.onNodeWithText(with(R.string.key_enable, R.string.settings_engine_exa))
            enable.assertIsOn().performScrollTo().performClick()
            enable.assertIsOff()
            save()
            settled { WebEngine.EXA !in current().webEngines }
            assertNull(current().aiProvider)
            compose
                .onNodeWithText(
                    state(R.string.settings_engine_exa, R.string.key_service_disabled),
                ).performScrollTo()
                .assertIsDisplayed()
        }

    @Test
    fun aChoiceThatCannotBeWrittenSaysSoInTheSummaryWithTryAgain() =
        open(R.string.settings_key_name_exa) {
            settingsData.writeFailures.set(1)
            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            waitForText(text(R.string.load_keys_services_failed))
            assertTrue(!shown(state(R.string.settings_engine_exa, R.string.key_service_enabled)))
            compose.onNodeWithText(text(R.string.load_keys_try_again)).performClick()
            waitForText(state(R.string.settings_engine_exa, R.string.key_service_enabled))
            settled { WebEngine.EXA in current().webEngines }
        }

    @Test
    fun theChoiceIsWrittenThoughTheActivityIsRecreatedDuringTheSave() {
        val gate = CompletableDeferred<Unit>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        saves,
                    ) { it }
                }
            }
            waitForText(text(R.string.settings_title))
            compose.openSection(SettingsSection.SECRETS)
            compose.onNodeWithText(text(R.string.settings_key_name_openai)).performScrollTo().performClick()
            compose.onNode(keyField).performTextInput("typed-key-a1B2")
            compose.onNodeWithText(with(R.string.key_enable, R.string.settings_ai_provider_openai)).assertIsOn()
            val before = secretsData.writes.get()
            secretsData.writeGate = gate
            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            idleUntil("the key's write started") { secretsData.writes.get() == before + 1 }

            // A rotation while the key's write waits: the dialog goes, the save goes on.
            scenario.recreate()
            gate.complete(Unit)
            settled { current().aiProvider == ProviderId.OPENAI }
            assertEquals("typed-key-a1B2", runBlocking { secrets.get(SecretId.OPENAI) }?.reveal())
        }
    }

    @Test
    fun aSourceKeyTickedSwitchesItsServiceOn() =
        open(R.string.settings_key_name_exa) {
            save()
            settled { SourceSwitch.WEB in current().enabledSources && WebEngine.EXA in current().webEngines }
            compose
                .onNodeWithText(
                    state(R.string.settings_engine_exa, R.string.key_service_enabled),
                ).performScrollTo()
                .assertIsDisplayed()
        }
}

/** A key dialog's field: the one text field but the settings' search box (#649). */
private val keyField = hasSetTextAction() and !hasTestTag(SEARCH_FIELD_TAG)
