package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.FlakyDataStore
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.idleUntil
import app.sleevesight.app.memorySettings
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.Secret
import app.sleevesight.core.genres.LabelSource
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Load keys* after a code is read (#763, #765): ③ which keys to save, ④ which services to switch
 * on, and the summary; the keys are saved only at ④'s *Complete*, and a key saved switches its
 * service on only as chosen there.
 */
@RunWith(RobolectricTestRunner::class)
class LoadKeysEnableTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    // Over stores whose writes a test can fail or hold back.
    private val settingsData = FlakyDataStore(MemoryStore())
    private val secretsData = FlakyDataStore(MemoryStore())
    private val settings = memorySettings(settingsData)
    private val secrets = SecretStore(secretsData, testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val anthropicKey = "sk-ant-api03-" + "a1B2_c3-D4".repeat(9)
    private val openAiKey = "sk-proj-" + "a1B2c3D4e5".repeat(5)
    private val googleKey = "AQ.Ab8RN6" + "Lq0_x-Yz.9".repeat(5)
    private val exaKey = "0c6d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f"
    private val lastFmKey = "0123456789abcdef0123456789abcdef"

    @Before
    fun nothingKeyedOn() {
        // As the keys' gate leaves a phone with no keys: every service that needs one off.
        runBlocking {
            settings.update {
                it.copy(
                    aiProvider = null,
                    enabledSources = it.enabledSources - SourceSwitch.LASTFM - SourceSwitch.WEB,
                    webEngines = emptySet(),
                    labelSources = it.labelSources - LabelSource.LASTFM,
                )
            }
        }
    }

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private fun current(): Settings = runBlocking { settings.settings.first() }

    private fun stored(id: SecretId): String? = runBlocking { secrets.get(id) }?.reveal()

    /** Opens *Load keys*, past its intro and words, reading [code] at once, and goes on to the keys to save. */
    private fun show(
        code: String,
        block: ActivityScenario<MainActivity>.() -> Unit,
    ) {
        val left = ArrayDeque(listOf(code))
        val scanner: QrScanner = { onScanned -> LaunchedEffect(Unit) { left.removeFirstOrNull()?.let(onScanned) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        saves,
                        scanner = scanner,
                    ) {
                        it
                    }
                }
            }
            waitForText(text(R.string.settings_title))
            compose.openSection(SettingsSection.SECRETS)
            compose.onNodeWithText(text(R.string.settings_scan_keys)).performScrollTo().performClick()
            waitForText(text(R.string.load_keys_start))
            compose.onNodeWithText(text(R.string.load_keys_start)).performClick()
            waitForText(text(R.string.load_keys_next))
            compose.onNodeWithText(text(R.string.load_keys_next)).performClick()
            waitForText(text(R.string.load_keys_read_title))
            compose.onNodeWithText(text(R.string.load_keys_next)).performClick()
            waitFor("the keys to save") { shown(text(R.string.load_keys_only_selected)) }
            scenario.block()
        }
    }

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) = idleUntil(what) { condition() }

    private fun waitForNode(matcher: SemanticsMatcher) =
        waitFor("a node: ${matcher.description}") { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) = waitForNode(hasText(text))

    /** Waits for the settings the choices apply, written in the background, to satisfy [condition]. */
    private fun settled(condition: () -> Boolean) = idleUntil("the settings written") { condition() }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** The start of the line under the services' table, before its links. */
    private val where by lazy { text(R.string.load_keys_where).substringBefore("%1") }

    private fun next() = compose.onNodeWithText(text(R.string.load_keys_next)).performClick()

    private fun complete() = compose.onNodeWithText(text(R.string.load_keys_complete)).performClick()

    private fun previous() = compose.onNodeWithText(text(R.string.load_keys_previous)).performClick()

    /** The row of the key [name] in [group], whose value is [key], on the keys to save. */
    private fun keyRow(
        name: Int,
        group: Int,
        key: String,
    ) = compose.onNodeWithText(
        "${text(name)}, ${text(group)}, ending ${key.takeLast(4)}, ${text(R.string.key_save_as_new)}",
    )

    /** The services' row of [service] in [group], as a screen reader reads it. */
    private fun row(
        service: Int,
        group: Int,
    ) = compose.onNodeWithText("${text(service)}, ${text(group)}")

    /** The summary's row of [service], [state]. */
    private fun state(
        service: Int,
        state: Int,
    ) = "${text(service)}, ${text(state)}"

    private fun toServices() {
        next()
        waitForText(text(R.string.load_keys_enable_question))
    }

    @Test
    fun oneAiKeyAndTheSourcesStartTickedAndCompleteSwitchesThemOn() =
        show("sleevesight-keys:v1\nanthropic=$anthropicKey\nlastfm=$lastFmKey\nexa=$exaKey") {
            toServices()
            row(R.string.settings_ai_provider_anthropic, R.string.key_group_ai).assertIsOn()
            row(R.string.settings_source_lastfm, R.string.key_group_music).assertIsOn()
            row(R.string.settings_engine_exa, R.string.key_group_search).assertIsOn()
            // A checkbox, not radios: nothing to say of one AI service at a time, or of others saved.
            for (absent in listOf(
                R.string.load_keys_one_ai,
                R.string.load_keys_configured,
            )) {
                assertTrue(!shown(text(absent)))
            }
            compose.onNodeWithText(where, substring = true).performScrollTo().assertIsDisplayed()
            // Nothing saved, and nothing switched on, yet.
            assertNull(stored(SecretId.ANTHROPIC))
            assertNull(current().aiProvider)

            complete()
            waitForText(text(R.string.load_keys_saved_title))
            assertEquals(anthropicKey, stored(SecretId.ANTHROPIC))
            settled { current().aiProvider == ProviderId.ANTHROPIC }
            val now = current()
            assertTrue(SourceSwitch.LASTFM in now.enabledSources && LabelSource.LASTFM in now.labelSources)
            assertTrue(SourceSwitch.WEB in now.enabledSources && WebEngine.EXA in now.webEngines)
            compose.onNodeWithText(text(R.string.load_keys_services_configured)).performScrollTo().assertIsDisplayed()
            for (service in listOf(
                R.string.settings_ai_provider_anthropic,
                R.string.settings_source_lastfm,
                R.string.settings_engine_exa,
            )) {
                compose
                    .onNodeWithText(
                        state(service, R.string.key_service_enabled),
                    ).performScrollTo()
                    .assertIsDisplayed()
            }
        }

    @Test
    fun severalAiKeysAreRadiosOfWhichOneOrNoneIsChosen() =
        show("sleevesight-keys:v1\nanthropic=$anthropicKey\nopenai=$openAiKey") {
            toServices()
            val anthropic = row(R.string.settings_ai_provider_anthropic, R.string.key_group_ai)
            val openAi = row(R.string.settings_ai_provider_openai, R.string.key_group_ai)
            anthropic.assertIsNotSelected()
            openAi.assertIsNotSelected()
            compose.onNodeWithText(text(R.string.load_keys_one_ai)).performScrollTo().assertIsDisplayed()

            openAi.performClick()
            openAi.assertIsSelected()
            anthropic.performClick()
            anthropic.assertIsSelected()
            openAi.assertIsNotSelected()
            // A selected radio can be unselected: no AI service then.
            anthropic.performClick()
            anthropic.assertIsNotSelected()

            complete()
            waitForText(text(R.string.load_keys_saved_title))
            settled { current().aiProvider == null }
            compose
                .onNodeWithText(state(R.string.settings_ai_provider_anthropic, R.string.key_service_disabled))
                .performScrollTo()
                .assertIsDisplayed()
        }

    @Test
    fun anAiServiceSavedBeforeIsAlreadyConfiguredAndReplacedWhenAnotherIsChosen() {
        runBlocking {
            secrets.set(SecretId.GOOGLE, Secret(googleKey))
            settings.update { it.copy(aiProvider = ProviderId.GOOGLE) }
        }
        show("sleevesight-keys:v1\nanthropic=$anthropicKey\nexa=$exaKey") {
            toServices()
            compose.onNodeWithText(text(R.string.load_keys_configured)).performScrollTo().assertIsDisplayed()
            val google = row(R.string.settings_ai_provider_google, R.string.key_group_ai)
            google.assertIsSelected()
            compose
                .onNodeWithText(
                    app.getString(R.string.key_current_ai, text(R.string.settings_ai_provider_google)),
                ).performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText(text(R.string.load_keys_one_ai)).performScrollTo().assertIsDisplayed()
            val anthropic = row(R.string.settings_ai_provider_anthropic, R.string.key_group_ai)
            anthropic.assertIsNotSelected()

            anthropic.performClick()
            google.assertIsNotSelected()
            complete()
            waitForText(text(R.string.load_keys_saved_title))
            settled { current().aiProvider == ProviderId.ANTHROPIC }
            // The AI service replaced is listed too, though its key was not loaded.
            compose
                .onNodeWithText(
                    state(R.string.settings_ai_provider_google, R.string.key_service_disabled),
                ).performScrollTo()
                .assertIsDisplayed()
            compose
                .onNodeWithText(
                    state(R.string.settings_ai_provider_anthropic, R.string.key_service_enabled),
                ).performScrollTo()
                .assertIsDisplayed()
            compose
                .onNodeWithText(
                    state(R.string.settings_engine_exa, R.string.key_service_enabled),
                ).performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun cancelOnTheServicesSavesNothingAndSwitchesNothing() =
        show("sleevesight-keys:v1\nanthropic=$anthropicKey\nlastfm=$lastFmKey") {
            val before = current()
            toServices()
            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()
            waitFor("the screen closed") { !shown(text(R.string.load_keys_enable_question)) }
            compose.waitForIdle()
            assertNull(stored(SecretId.ANTHROPIC))
            assertNull(stored(SecretId.LASTFM))
            assertEquals(before, current())
        }

    @Test
    fun previousFromTheServicesKeepsTheTicksOfTheKeys() =
        show("sleevesight-keys:v1\nanthropic=$anthropicKey\nlastfm=$lastFmKey") {
            val anthropic = keyRow(R.string.settings_ai_provider_anthropic, R.string.key_group_ai, anthropicKey)
            anthropic.performClick()
            anthropic.assertIsOff()
            toServices()
            assertTrue(!shown("${text(R.string.settings_ai_provider_anthropic)}, ${text(R.string.key_group_ai)}"))

            previous()
            waitForText(text(R.string.load_keys_only_selected))
            keyRow(R.string.settings_ai_provider_anthropic, R.string.key_group_ai, anthropicKey).assertIsOff()
            keyRow(R.string.settings_source_lastfm, R.string.key_group_music, lastFmKey).assertIsOn()
            // Nothing saved by going there and back.
            assertNull(stored(SecretId.LASTFM))
            toServices()
            row(R.string.settings_source_lastfm, R.string.key_group_music).assertIsOn()
            assertTrue(!shown("${text(R.string.settings_ai_provider_anthropic)}, ${text(R.string.key_group_ai)}"))
        }

    @Test
    fun onlyTheKeysTickedAreSavedAndNextIsDimmedWhileNoneIs() =
        show("sleevesight-keys:v1\nanthropic=$anthropicKey\nlastfm=$lastFmKey") {
            val new = text(R.string.key_save_as_new)
            val anthropicRow = "${text(R.string.settings_ai_provider_anthropic)}, ${text(R.string.key_group_ai)}"
            val anthropic = compose.onNodeWithText("$anthropicRow, ending ${anthropicKey.takeLast(4)}, $new")
            val lastFmRow = "${text(R.string.settings_source_lastfm)}, ${text(R.string.key_group_music)}"
            val lastFm = compose.onNodeWithText("$lastFmRow, ending ${lastFmKey.takeLast(4)}, $new")
            anthropic.assertIsOn().performClick()
            anthropic.assertIsOff()
            lastFm.performClick()
            compose.onNodeWithText(text(R.string.load_keys_next)).assertIsNotEnabled()

            lastFm.performClick()
            toServices()
            // Only the key ticked has a row.
            assertTrue(!shown(anthropicRow))
            complete()
            waitForText(text(R.string.load_keys_saved_title_one))
            assertNull(stored(SecretId.ANTHROPIC))
            assertEquals(lastFmKey, stored(SecretId.LASTFM))
        }

    @Test
    fun choicesThatCannotBeWrittenSaySoInTheSummaryAndTryAgainWritesThem() =
        show("sleevesight-keys:v1\nlastfm=$lastFmKey") {
            toServices()
            settingsData.writeFailures.set(1)
            complete()
            // Not the services as asked for: that they could not be switched, with *Try again*.
            waitForText(text(R.string.load_keys_services_failed))
            val lastFmOn = state(R.string.settings_source_lastfm, R.string.key_service_enabled)
            assertTrue(!shown(lastFmOn))
            assertTrue(SourceSwitch.LASTFM !in current().enabledSources)

            compose.onNodeWithText(text(R.string.load_keys_try_again)).performClick()
            waitForText(lastFmOn)
            assertTrue(SourceSwitch.LASTFM in current().enabledSources)
            assertTrue(!shown(text(R.string.load_keys_try_again)))
        }

    @Test
    fun afterAFailedSaveUntickingTheKeysLeftGoesOnWithThoseSaved() =
        show("sleevesight-keys:v1\nanthropic=$anthropicKey\nlastfm=$lastFmKey") {
            toServices()
            // Anthropic's write fails; Last.fm's is saved.
            secretsData.writeFailures.set(1)
            complete()
            waitForText(app.getString(R.string.setup_failed, text(R.string.settings_key_name_anthropic)))
            assertEquals(lastFmKey, stored(SecretId.LASTFM))
            previous()
            waitForText(text(R.string.load_keys_only_selected))
            keyRow(R.string.settings_ai_provider_anthropic, R.string.key_group_ai, anthropicKey).performClick()
            val writes = secretsData.writes.get()
            compose.onNodeWithText(text(R.string.load_keys_next)).assertIsEnabled().performClick()
            waitForText(text(R.string.load_keys_enable_question))
            // Last.fm's row alone.
            val anthropicRow = "${text(R.string.settings_ai_provider_anthropic)}, ${text(R.string.key_group_ai)}"
            assertTrue(!shown(anthropicRow))
            row(R.string.settings_source_lastfm, R.string.key_group_music).assertIsOn()
            complete()
            waitForText(text(R.string.load_keys_saved_title_one))
            // No key written again.
            assertEquals(writes, secretsData.writes.get())
            assertNull(stored(SecretId.ANTHROPIC))
        }

    @Test
    fun aLoadOfSourcesOnlyShowsNoAiServices() {
        runBlocking {
            secrets.set(SecretId.GOOGLE, Secret(googleKey))
            settings.update { it.copy(aiProvider = ProviderId.GOOGLE) }
        }
        show("sleevesight-keys:v1\nlastfm=$lastFmKey") {
            toServices()
            for (absent in listOf(
                R.string.load_keys_configured,
                R.string.load_keys_one_ai,
            )) {
                assertTrue(!shown(text(absent)))
            }
            assertTrue(!shown("${text(R.string.settings_ai_provider_google)}, ${text(R.string.key_group_ai)}"))
            complete()
            waitForText(text(R.string.load_keys_saved_title_one))
            assertEquals(ProviderId.GOOGLE, current().aiProvider)
        }
    }

    @Test
    fun anAiServiceOnWithNoKeyIsNamedAndANewAiKeyStartsUnselected() {
        runBlocking { settings.update { it.copy(aiProvider = ProviderId.OLLAMA) } }
        show("sleevesight-keys:v1\nanthropic=$anthropicKey") {
            toServices()
            compose
                .onNodeWithText(app.getString(R.string.key_current_ai, text(R.string.settings_ai_provider_ollama)))
                .performScrollTo()
                .assertIsDisplayed()
            row(R.string.settings_ai_provider_anthropic, R.string.key_group_ai).assertIsNotSelected()
            complete()
            waitForText(text(R.string.load_keys_saved_title_one))
            assertEquals(ProviderId.OLLAMA, current().aiProvider)
        }
    }

    @Test
    fun theRowsAndButtonsAreLockedWhileCompleteSavesTheKeys() =
        show("sleevesight-keys:v1\nlastfm=$lastFmKey") {
            toServices()
            val keys = CompletableDeferred<Unit>()
            val keyWrites = secretsData.writes.get()
            secretsData.writeGate = keys
            complete()
            waitFor("the key's write held") { secretsData.writes.get() > keyWrites }
            row(R.string.settings_source_lastfm, R.string.key_group_music).assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.load_keys_previous)).assertIsNotEnabled()
            keys.complete(Unit)
            waitForText(text(R.string.load_keys_saved_title_one))
        }

    @Test
    fun theRowsAreLockedWhileCompleteWritesTheChoices() =
        show("sleevesight-keys:v1\nlastfm=$lastFmKey") {
            toServices()
            val choices = CompletableDeferred<Unit>()
            val settingsWrites = settingsData.writes.get()
            settingsData.writeGate = choices
            complete()
            // The choices are written once the keys' save has come back on the main thread.
            waitFor("the choices' write held") {
                compose.waitForIdle()
                settingsData.writes.get() > settingsWrites
            }
            row(R.string.settings_source_lastfm, R.string.key_group_music).assertIsNotEnabled()
            choices.complete(Unit)
            waitForText(text(R.string.load_keys_saved_title_one))
        }

    @Test
    fun aRotationWhileCompleteSavesStillSwitchesTheServicesOn() =
        show("sleevesight-keys:v1\nlastfm=$lastFmKey") {
            toServices()
            val keys = CompletableDeferred<Unit>()
            val keyWrites = secretsData.writes.get()
            secretsData.writeGate = keys
            complete()
            waitFor("the key's write held") { secretsData.writes.get() > keyWrites }
            // The screen goes with the activity, before the key is saved.
            recreate()
            keys.complete(Unit)
            settled { SourceSwitch.LASTFM in current().enabledSources }
            assertEquals(lastFmKey, stored(SecretId.LASTFM))
        }

    @Test
    fun theServicesNameTheirSectionsWithoutLinksAndTheSummaryLinksThem() =
        show("sleevesight-keys:v1\nlastfm=$lastFmKey") {
            toServices()
            // On ④, nothing saved yet: plain text, as a link would leave *Load keys* (#765).
            val line =
                compose
                    .onNode(hasText(where, substring = true), useUnmergedTree = true)
                    .performScrollTo()
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
            assertTrue(line.getLinkAnnotations(0, line.length).isEmpty())

            complete()
            waitForText(text(R.string.load_keys_saved_title_one))
            compose
                .onNode(hasText(where, substring = true), useUnmergedTree = true)
                .performScrollTo()
                .performFirstLinkClick { (it.item as? LinkAnnotation.Clickable)?.tag == SettingsTarget.genres.linkTag }
            waitFor("the screen closed") { !shown(text(R.string.load_keys_saved_title_one)) }
            // *Genres* is open: its first switch shows.
            waitForText(text(R.string.settings_show_genres))
        }
}
