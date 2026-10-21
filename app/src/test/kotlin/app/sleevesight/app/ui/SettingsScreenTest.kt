package app.sleevesight.app.ui

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
import app.sleevesight.app.ui.settings.KeyDialogs
import app.sleevesight.app.ui.settings.SettingsDialog
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.ReasoningEffort
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.ProviderException
import java.util.concurrent.CountDownLatch
import javax.crypto.SecretKey
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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

    @Suppress("LongParameterList") // Each test sets what it needs, and leaves the rest.
    private fun show(
        onOpenCredits: () -> Unit = {},
        settingsStore: SettingsStore = settings,
        clearHistory: () -> Deferred<Boolean> = ::clearedAtOnce,
        secretStore: SecretStore = secrets,
        block: ActivityScenario<MainActivity>.() -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settingsStore, secretStore, emptyMap(), clearHistory, saves, onOpenCredits) {
                        "Label of $it"
                    }
                }
            }
            // The screen draws once the settings are read.
            waitForText(text(R.string.settings_title))
            scenario.block()
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
            // A screen reader announces it.
            val message = compose.onNodeWithText(text(R.string.settings_change_failed))
            message.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

            // A second failure in a row draws the message afresh, so it is announced again.
            val first = message.fetchSemanticsNode().id
            flaky.writeFailures.set(1)
            row.performClick()
            waitFor("the failure message drawn afresh") {
                compose
                    .onAllNodesWithText(text(R.string.settings_change_failed))
                    .fetchSemanticsNodes()
                    .any { it.id != first }
            }
            assertEquals(false, current(store).lookUpInBackground)

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
            compose.onNodeWithText(anthropic(R.string.settings_ai_effort_of)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_effort_high)).performClick()

            waitForText(text(R.string.settings_change_failed))
            // The dialog has closed, and the row still shows the stored effort.
            assertEquals(0, compose.onAllNodesWithText(text(R.string.settings_effort_high)).fetchSemanticsNodes().size)
            compose.onNodeWithText(text(R.string.settings_effort_low)).performScrollTo().assertIsDisplayed()
            assertEquals(ReasoningEffort.LOW, current(store).effort(ProviderId.ANTHROPIC))
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
                activity.setContent {
                    SettingsScreen(settings, failingSecrets, emptyMap(), ::clearedAtOnce, saves) { it }
                }
            }
            waitForText(text(R.string.settings_title))
            compose.onNodeWithText(text(R.string.settings_key_name_exa)).performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextInput("exa-key")
            compose.onNodeWithText(text(R.string.settings_save)).performClick()

            waitForText(text(R.string.settings_key_save_failed))
            compose.onNodeWithText(text(R.string.settings_save)).assertIsDisplayed()
            assertEquals(null, runBlocking { failingSecrets.get(SecretId.EXA) })
            // A key store that failed: *Reset saved keys* is offered.
            waitForText(text(R.string.settings_reset_keys))
        }
    }

    @Test
    fun aKeyThatCannotBeWrittenSaysSoAndOffersNoReset() {
        val flaky = FlakyDataStore(stores.open("flaky-secrets"))
        val flakySecrets = SecretStore(flaky, testCipher())
        show(secretStore = flakySecrets) {
            flaky.writeFailures.set(1)
            compose.onNodeWithText(text(R.string.settings_key_name_exa)).performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextInput("exa-key")
            compose.onNodeWithText(text(R.string.settings_save)).performClick()

            waitForText(text(R.string.settings_key_write_failed))
            compose.onNodeWithText(text(R.string.settings_save)).assertIsDisplayed()
            assertEquals(null, runBlocking { flakySecrets.get(SecretId.EXA) })
            // The file could not be written, which a reset would not cure: none is offered.
            for (absent in listOf(R.string.settings_reset_keys, R.string.settings_key_save_failed)) {
                assertTrue(compose.onAllNodesWithText(text(absent)).fetchSemanticsNodes().isEmpty())
            }
        }
    }

    @Test
    fun aSaveCannotBeCancelledWhileItRunsAndAKeyStoreFailureThenOffersReset() {
        val release = CountDownLatch(1)
        // The key store answers only once released, and then fails.
        val slow =
            object : SoftwareKeySource() {
                override fun find(): SecretKey? {
                    release.await()
                    throw ProviderException("key store down")
                }
            }
        val slowSecrets = SecretStore(stores.open("slow-secrets"), SecretCipher(slow), legacyKeystore = false)
        try {
            show(secretStore = slowSecrets) {
                compose.onNodeWithText(text(R.string.settings_key_name_exa)).performScrollTo().performClick()
                compose.onNode(hasSetTextAction()).performTextInput("exa-key")
                compose.onNodeWithText(text(R.string.settings_save)).performClick()

                // While the save runs, *Cancel* is refused, so it cannot seem to cancel a save that goes on.
                val cancel = hasText(text(R.string.settings_cancel))
                waitForNode(cancel and isNotEnabled())
                compose.onNode(cancel).performClick()
                compose.onNode(hasSetTextAction()).assertExists()

                release.countDown()
                waitForText(text(R.string.settings_key_save_failed))
                waitForText(text(R.string.settings_reset_keys))
            }
        } finally {
            // Also when a step above fails, so no I/O thread stays blocked for the rest of the run.
            release.countDown()
        }
    }

    @Test
    fun aResetAfterAKeyStoreFailureClearsTheOffer() {
        // The key store fails until its key is deleted, as a corrupt key blob would.
        val keys =
            object : SoftwareKeySource() {
                override fun find(): SecretKey? =
                    if (deletions == 0) throw ProviderException("invalid key blob") else super.find()
            }
        val failingSecrets = SecretStore(stores.open("failing-secrets"), SecretCipher(keys), legacyKeystore = false)
        show(secretStore = failingSecrets) {
            compose.onNodeWithText(text(R.string.settings_key_name_exa)).performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextInput("exa-key")
            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            waitForText(text(R.string.settings_key_save_failed))
            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()

            waitForText(text(R.string.settings_reset_keys))
            compose.onNodeWithText(text(R.string.settings_reset_keys)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_reset)).performClick()
            waitFor("Reset saved keys no longer offered") {
                compose.onAllNodesWithText(text(R.string.settings_reset_keys)).fetchSemanticsNodes().isEmpty()
            }
            assertEquals(1, keys.deletions)
        }
    }

    @Test
    fun aResetThatEndsAfterItsDialogHasGoneStillClearsTheOffer() {
        val flaky = FlakyDataStore(stores.open("gated-reset-secrets"))
        val gatedSecrets = SecretStore(flaky, testCipher())
        val gate = CompletableDeferred<Unit>()
        // The screen's state, which outlives the dialog: *Reset saved keys* offered after a key
        // store failure.
        var sealFailed by mutableStateOf(true)
        var dialogShown by mutableStateOf(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    if (dialogShown) {
                        KeyDialogs(
                            SettingsDialog.ResetKeys,
                            emptyMap(),
                            gatedSecrets,
                            saves,
                            scanner = { _, _ -> },
                            onSealFailed = { sealFailed = it },
                            open = {},
                            onDismiss = {},
                        )
                    }
                }
            }
            flaky.writeGate = gate
            waitForText(text(R.string.settings_reset))
            compose.onNodeWithText(text(R.string.settings_reset)).performClick()
            waitFor("the reset's write started") { flaky.writes.get() == 1 }

            // The dialog goes while the reset waits; the state that observes it stays.
            dialogShown = false
            compose.waitForIdle()
            gate.complete(Unit)
            waitFor("Reset saved keys no longer offered") { !sealFailed }
        }
    }

    @Test
    fun beforeApi31AFileThatCannotBeReadIsAStorageFailureWithNoResetOffered() {
        val unreadable =
            object : DataStore<Preferences> by stores.open("unreadable-secrets") {
                override val data = flow<Preferences> { throw IOException("cannot read") }
            }
        val legacySecrets =
            SecretStore(unreadable, SecretCipher(SoftwareKeySource(current = null)), legacyKeystore = true)
        show(secretStore = legacySecrets) {
            compose.onNodeWithText(text(R.string.settings_key_name_exa)).performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextInput("exa-key")
            compose.onNodeWithText(text(R.string.settings_save)).performClick()

            waitForText(text(R.string.settings_key_write_failed))
            for (absent in listOf(R.string.settings_reset_keys, R.string.settings_key_save_failed)) {
                assertTrue(compose.onAllNodesWithText(text(absent)).fetchSemanticsNodes().isEmpty())
            }
        }
    }

    @Test
    fun aChangeIsSavedThoughTheScreenGoesFirst() {
        val flaky = FlakyDataStore(stores.open("gated-settings"))
        val store = SettingsStore(flaky)
        val gate = CompletableDeferred<Unit>()
        show(settingsStore = store) {
            flaky.writeGate = gate
            compose.onNodeWithText(text(R.string.settings_background)).performScrollTo().performClick()
            waitFor("the change's write started") { flaky.writes.get() == 1 }

            // The screen goes while the write waits, as when the user leaves it.
            onActivity { activity -> activity.setContent {} }
            compose.waitForIdle()
            gate.complete(Unit)
            waitFor("look-up in the background stored on") { current(store).lookUpInBackground }
        }
    }

    @Test
    fun aKeyIsSavedThoughTheScreenGoesFirst() {
        val flaky = FlakyDataStore(stores.open("gated-secrets"))
        val gatedSecrets = SecretStore(flaky, testCipher())
        val gate = CompletableDeferred<Unit>()
        show(secretStore = gatedSecrets) {
            flaky.writeGate = gate
            compose.onNodeWithText(text(R.string.settings_key_name_exa)).performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextInput("exa-key")
            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            waitFor("the key's write started") { flaky.writes.get() == 1 }

            // The dialog and the screen go while the write waits.
            onActivity { activity -> activity.setContent {} }
            compose.waitForIdle()
            gate.complete(Unit)
            waitFor("the Exa key stored") { runBlocking { gatedSecrets.get(SecretId.EXA) }?.reveal() == "exa-key" }
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
                activity.setContent { SettingsScreen(settings, resetting, emptyMap(), ::clearedAtOnce, saves) { it } }
            }
            waitForText(text(R.string.settings_reset_keys))
            compose.onNodeWithText(text(R.string.settings_key_unreadable)).performScrollTo().assertIsDisplayed()
            // A key that cannot be read can still be removed, alone or with the others.
            compose.onNodeWithContentDescription(removeKey(R.string.settings_key_name_lastfm)).assertExists()
            compose.onNodeWithText(text(R.string.settings_remove_all_keys)).assertExists()

            compose.onNodeWithText(text(R.string.settings_reset_keys)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_reset_keys_explained)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.settings_reset)).performClick()

            waitFor("Reset saved keys gone") {
                compose.onAllNodesWithText(text(R.string.settings_reset_keys)).fetchSemanticsNodes().isEmpty()
            }
            assertEquals(SecretState.ABSENT, runBlocking { resetting.states.first() }.getValue(SecretId.LASTFM))
        }
    }

    @Test
    fun aStoredKeysRowRemovesThatKeyOnceConfirmed() {
        runBlocking {
            settings.update { it.copy(aiProvider = ProviderId.ANTHROPIC) }
            secrets.set(SecretId.LASTFM, Secret("lastfm-key"))
            secrets.set(SecretId.EXA, Secret("exa-key"))
        }
        show {
            val removeLastFm = removeKey(R.string.settings_key_name_lastfm)
            waitForNode(hasContentDescription(removeLastFm))
            // Only a row with a key stored offers it: not the Anthropic key's, with none saved.
            compose.onNodeWithContentDescription(removeKey(R.string.settings_key_name_exa)).assertExists()
            val removeAnthropic = removeKey(R.string.settings_key_name_anthropic)
            assertTrue(compose.onAllNodesWithContentDescription(removeAnthropic).isEmpty())

            compose.onNodeWithContentDescription(removeLastFm).performScrollTo().performClick()
            val explained = app.getString(R.string.settings_key_remove_explained, text(R.string.settings_source_lastfm))
            compose.onNodeWithText(explained).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()
            compose.waitForIdle()
            assertEquals(SecretState.SAVED, runBlocking { secrets.states.first() }.getValue(SecretId.LASTFM))

            compose.onNodeWithContentDescription(removeLastFm).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_remove)).performClick()
            waitFor("the Last.fm key removed") { runBlocking { secrets.get(SecretId.LASTFM) } == null }
            waitFor("the Last.fm key's button gone") {
                compose.onAllNodesWithContentDescription(removeLastFm).isEmpty()
            }
            assertEquals("exa-key", runBlocking { secrets.get(SecretId.EXA) }?.reveal())

            // The AI service's key row offers it too, once its key is saved.
            runBlocking { secrets.set(SecretId.ANTHROPIC, Secret("anthropic-key")) }
            waitForNode(hasContentDescription(removeAnthropic))
        }
    }

    @Test
    fun withNoServiceSwitchedOnASavedAiKeyKeepsItsRowToRemoveIt() {
        runBlocking { secrets.set(SecretId.ANTHROPIC, Secret("anthropic-key")) }
        show {
            val removeAnthropic = removeKey(R.string.settings_key_name_anthropic)
            waitForNode(hasContentDescription(removeAnthropic))
            assertEquals(null, current().aiProvider)
            compose.onNodeWithText(text(R.string.settings_key_name_anthropic)).performScrollTo().assertIsDisplayed()

            compose.onNodeWithContentDescription(removeAnthropic).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_remove)).performClick()
            waitFor("the Anthropic key removed") { runBlocking { secrets.get(SecretId.ANTHROPIC) } == null }
            waitFor("its button gone") { compose.onAllNodesWithContentDescription(removeAnthropic).isEmpty() }
            // The row stays, under Anthropic's switch, to enter a key again.
            compose.onNodeWithText(text(R.string.settings_key_name_anthropic)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun aKeyThatCannotBeRemovedKeepsTheConfirmationOpenAndSaysSo() {
        val flaky = FlakyDataStore(stores.open("flaky-secrets"))
        val flakySecrets = SecretStore(flaky, testCipher())
        runBlocking { flakySecrets.set(SecretId.EXA, Secret("exa-key")) }
        show(secretStore = flakySecrets) {
            val removeExa = removeKey(R.string.settings_key_name_exa)
            waitForNode(hasContentDescription(removeExa))
            flaky.writeFailures.set(1)
            compose.onNodeWithContentDescription(removeExa).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_remove)).performClick()

            waitForText(text(R.string.settings_key_remove_failed))
            compose.onNodeWithText(text(R.string.settings_remove)).assertIsDisplayed()
            assertEquals("exa-key", runBlocking { flakySecrets.get(SecretId.EXA) }?.reveal())
        }
    }

    @Test
    fun removeAllApiKeysShowsWhileAnyKeyIsStored() =
        show {
            val row = text(R.string.settings_remove_all_keys)
            compose.onNodeWithText(text(R.string.credits_title)).performScrollTo()
            assertTrue(compose.onAllNodesWithText(row).fetchSemanticsNodes().isEmpty())

            runBlocking { secrets.set(SecretId.EXA, Secret("exa-key")) }
            waitForText(row)
            // Not the row above the list, which a failure brings.
            assertTrue(compose.onAllNodesWithText(text(R.string.settings_reset_keys)).fetchSemanticsNodes().isEmpty())

            compose.onNodeWithText(row).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_reset_keys_explained)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()
            compose.waitForIdle()
            assertEquals("exa-key", runBlocking { secrets.get(SecretId.EXA) }?.reveal())

            compose.onNodeWithText(row).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_remove_all)).performClick()
            waitFor("the row gone") { compose.onAllNodesWithText(row).fetchSemanticsNodes().isEmpty() }
            assertTrue(runBlocking { secrets.states.first() }.values.all { it == SecretState.ABSENT })
        }

    @Test
    fun removeAllApiKeysThatFailsSaysSo() {
        val failing =
            object : SoftwareKeySource() {
                override fun delete(): Unit = throw ProviderException("key store down")
            }
        val failingSecrets = SecretStore(stores.open("failing-secrets"), SecretCipher(failing))
        runBlocking { failingSecrets.set(SecretId.LASTFM, Secret("lastfm-key")) }
        show(secretStore = failingSecrets) {
            waitForText(text(R.string.settings_remove_all_keys))
            compose.onNodeWithText(text(R.string.settings_remove_all_keys)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_remove_all)).performClick()
            waitForText(text(R.string.settings_remove_all_keys_failed))
            compose.onNodeWithText(text(R.string.settings_remove_all)).assertIsDisplayed()
            // Only the phone's key could not be deleted: the API keys are gone, as the message allows.
            assertTrue(runBlocking { failingSecrets.states.first() }.values.all { it == SecretState.ABSENT })
        }
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
            waitFor("the history kept forever") { current().historyLifetime == null }

            compose.onNodeWithText(text(R.string.settings_session_gap)).performScrollTo().performClick()
            compose.onNodeWithText(app.resources.getQuantityString(R.plurals.settings_hours, 1, 1)).performClick()
            waitFor("a session gap of 1 hour stored") { current().sessionGap == 1.hours }
        }

    @Test
    fun thePauseOfAutomaticWritingIsChosen() =
        show {
            // The cost of automatic writing is said where it is set.
            compose.onNodeWithText(text(R.string.settings_write_automatically_summary)).performScrollTo()
            // The summary says what the count chosen does: the default, 5, at first, with nothing
            // written away while *Look up in the background* is off.
            compose.onNodeWithText(pauseSummary(5, background = false)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(R.string.settings_pause_auto_write)).performScrollTo().performClick()
            val ten = app.resources.getQuantityString(R.plurals.settings_track_changes_away, 10, 10)
            compose.onNodeWithText(ten).performClick()
            waitFor("a pause after 10 track changes stored") { current().pauseAutoWriteAfter == 10 }
            compose.onNodeWithText(pauseSummary(10, background = false)).performScrollTo().assertIsDisplayed()

            // With it on, the count bounds what is written away.
            compose.onNodeWithText(text(R.string.settings_background)).performScrollTo().performClick()
            waitFor("look-up in the background stored on") { current().lookUpInBackground }
            compose.onNodeWithText(pauseSummary(10, background = true)).performScrollTo().assertIsDisplayed()

            compose.onNodeWithText(text(R.string.settings_pause_auto_write)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_pause_auto_write_off)).performClick()
            waitFor("the pause off") { current().pauseAutoWriteAfter == null }
            // Under *Off*, nothing pauses, so no count is explained.
            compose.onNodeWithText(pauseSummary(null, background = true)).performScrollTo().assertIsDisplayed()
        }

    @Test
    fun aPauseAfterOneTrackChangeIsSummedUpInTheSingular() {
        runBlocking { settings.update { it.copy(pauseAutoWriteAfter = 1, lookUpInBackground = true) } }
        show {
            // "Up to": under *When I skip*, a natural end is counted but not written.
            val written = pauseSummary(1, background = true)
            assertTrue(written, written.contains("up to 1 track is written"))
            compose.onNodeWithText(written).performScrollTo().assertIsDisplayed()

            compose.onNodeWithText(text(R.string.settings_background)).performScrollTo().performClick()
            waitFor("look-up in the background stored off") { !current().lookUpInBackground }
            val away = pauseSummary(1, background = false)
            assertTrue(away, away.contains("nothing is written while you are away"))
            compose.onNodeWithText(away).performScrollTo().assertIsDisplayed()
        }
    }

    // The summary of *Pause automatic writing after* for [count], or for *Off*, with *Look up in
    // the background* as [background].
    private fun pauseSummary(
        count: Int?,
        background: Boolean,
    ): String {
        val counted = text(R.string.settings_pause_auto_write_summary)
        if (count == null) return counted
        val plural =
            if (background) {
                R.plurals.settings_pause_auto_write_summary_count
            } else {
                R.plurals.settings_pause_auto_write_summary_count_foreground
            }
        return "$counted ${app.resources.getQuantityString(plural, count, count)}"
    }

    @Test
    fun aStoredValueOutsideTheOptionsIsShownAsItIs() {
        runBlocking { settings.update { it.copy(historyLifetime = 12.hours, sessionGap = 45.minutes) } }
        show {
            val twelveHours = app.resources.getQuantityString(R.plurals.settings_hours, 12, 12)
            compose.onNodeWithText(twelveHours).performScrollTo().assertIsDisplayed()
            val minutes = app.resources.getQuantityString(R.plurals.settings_minutes, 45, 45)
            compose.onNodeWithText(minutes).performScrollTo().assertIsDisplayed()

            // And offered among the options.
            compose.onNodeWithText(text(R.string.settings_keep_history)).performScrollTo().performClick()
            compose.onAllNodesWithText(twelveHours).assertCountEquals(2)
        }
    }

    @Test
    fun clearHistoryAsksFirst() {
        var cleared = 0
        val clear = {
            cleared++
            CompletableDeferred(true)
        }
        show(clearHistory = clear) {
            compose.onNodeWithText(text(R.string.settings_clear_history)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_clear_history_explained)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()
            compose.waitForIdle()
            assertEquals(0, cleared)

            compose.onNodeWithText(text(R.string.settings_clear_history)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_clear)).performClick()
            waitFor("one clear asked for") { cleared == 1 }
            waitForText(text(R.string.settings_clear_history_done))
            assertEquals(
                0,
                compose.onAllNodesWithText(text(R.string.settings_clear_history_failed)).fetchSemanticsNodes().size,
            )
        }
    }

    @Test
    fun aFailedClearSaysSoUntilOneSucceeds() {
        var succeeds = false
        show(clearHistory = { CompletableDeferred(succeeds) }) {
            compose.onNodeWithText(text(R.string.settings_clear_history)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_clear)).performClick()
            waitForText(text(R.string.settings_clear_history_failed))

            succeeds = true
            compose.onNodeWithText(text(R.string.settings_clear_history)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_clear)).performClick()
            waitFor("the clear failure message gone") {
                compose.onAllNodesWithText(text(R.string.settings_clear_history_failed)).fetchSemanticsNodes().isEmpty()
            }
            waitForText(text(R.string.settings_clear_history_done))
        }
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
    fun thePrivacyPolicyRowOpensThePolicyOnTheSiteInTheBrowser() {
        show {
            compose.onNodeWithText(text(R.string.settings_privacy)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(PRIVACY_POLICY_SHOWN).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.settings_privacy)).performClick()

            var started: Intent? = null
            onActivity { started = shadowOf(it).nextStartedActivity }
            assertEquals(Intent.ACTION_VIEW, started?.action)
            assertEquals("https://sleevesight.app/privacy.html", started?.dataString)
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

    /** The delete button's description on the key [name]'s row. */
    private fun removeKey(name: Int) = app.getString(R.string.settings_key_remove, text(name))

    private fun ComposeTestRule.onAllNodesWithContentDescription(description: String) =
        onAllNodes(hasContentDescription(description)).fetchSemanticsNodes()

    /** *Clear history* done at once. */
    private fun clearedAtOnce(): Deferred<Boolean> = CompletableDeferred(true)

    private fun ComposeTestRule.onAllNodesWithSubstring(text: String) =
        onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
}
