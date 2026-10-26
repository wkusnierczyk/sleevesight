package app.sleevesight.app.ui

import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import app.sleevesight.app.idleUntil
import app.sleevesight.app.secrets.SecretCipher
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretState
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.KeyDialogs
import app.sleevesight.app.ui.settings.SettingsDialog
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.Secret
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.robolectric.shadows.ShadowDialog
import java.security.ProviderException
import javax.crypto.SecretKey

/**
 * *Purge keys* (#446): its row under *Secrets*, which says why to use it after a failure, and its
 * screen: the keys it deletes, the deletion, what it did, and a deletion that fails.
 */
@RunWith(RobolectricTestRunner::class)
class PurgeKeysTest {
    // The v2 rule runs effects on a `StandardTestDispatcher`, on the main thread, as the phone does:
    // the v1 rule's unconfined dispatcher resumed them on the key store's thread.
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private fun text(id: Int) = app.getString(id)

    private fun show(
        secretStore: SecretStore = secrets,
        block: ActivityScenario<MainActivity>.() -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secretStore, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            waitForText(text(R.string.settings_title))
            compose.openSection(SettingsSection.SECRETS)
            scenario.block()
        }
    }

    /**
     * Waits for [condition], failing, naming [what], after five seconds. What the screens show is
     * read and saved on DataStore's and the key store's threads: each step runs the main looper and
     * advances its clock ([idleUntil]), so the result posted back to it arrives.
     */
    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) = idleUntil(what) { condition() }

    private fun waitForNode(matcher: SemanticsMatcher) =
        waitFor("a node: ${matcher.description}") { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) = waitForNode(hasText(text))

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun openPurge() {
        waitForText(text(R.string.settings_remove_all_keys))
        compose.onNodeWithText(text(R.string.settings_remove_all_keys)).performScrollTo().performClick()
        waitForText(text(R.string.purge_keys_delete))
    }

    /** How the screen lists the key [name] ending in [key]'s last four characters. */
    private fun listed(
        name: Int,
        key: String,
    ) = app.getString(R.string.key_ending, text(name), key.takeLast(4))

    private fun allAbsent(store: SecretStore = secrets) =
        runBlocking { store.states.first() }.values.all { it == SecretState.ABSENT }

    private val exaKey = "0c6d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f"
    private val lastFmKey = "0123456789abcdef0123456789abcdef"

    @Test
    fun purgeKeysShowsWhileAnyKeyIsStoredSayingOnlyWhatItDoes() =
        show {
            val row = text(R.string.settings_remove_all_keys)
            waitForText(text(R.string.settings_scan_keys))
            assertTrue(!shown(row))

            runBlocking { secrets.set(SecretId.EXA, Secret(exaKey)) }
            waitForText(row)
            // No failure brought it.
            compose.onNodeWithText(text(R.string.settings_remove_all_keys_value)).assertExists()
            assertTrue(!shown(text(R.string.settings_reset_keys_value)))
        }

    @Test
    fun theScreenListsTheKeysItDeletesAndCancelKeepsThem() {
        runBlocking {
            secrets.set(SecretId.EXA, Secret(exaKey))
            secrets.set(SecretId.LASTFM, Secret(lastFmKey))
        }
        show {
            openPurge()
            waitForText(listed(R.string.settings_key_name_exa, exaKey))
            compose.onNodeWithText(listed(R.string.settings_key_name_lastfm, lastFmKey)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.purge_keys_listed)).assertIsDisplayed()
            // No key is shown whole.
            assertTrue(compose.onAllNodes(hasText(exaKey, substring = true)).fetchSemanticsNodes().isEmpty())

            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()
            waitFor("the screen closed") { !shown(text(R.string.purge_keys_delete)) }
            assertEquals(exaKey, runBlocking { secrets.get(SecretId.EXA) }?.reveal())
        }
    }

    @Test
    fun theScreenIsSecure() {
        runBlocking { secrets.set(SecretId.EXA, Secret(exaKey)) }
        show {
            openPurge()
            val flags =
                ShadowDialog
                    .getLatestDialog()
                    .window!!
                    .attributes.flags
            assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }

    @Test
    fun deleteDeletesEveryKeyAndSaysWhichWereDeleted() {
        runBlocking {
            secrets.set(SecretId.EXA, Secret(exaKey))
            secrets.set(SecretId.LASTFM, Secret(lastFmKey))
        }
        show {
            openPurge()
            waitForText(listed(R.string.settings_key_name_exa, exaKey))
            compose.onNodeWithText(text(R.string.purge_keys_delete)).performClick()

            waitForText(text(R.string.purge_keys_done_title))
            compose.onNodeWithText(text(R.string.purge_keys_done)).assertIsDisplayed()
            // The keys it deleted, as listed before: the store no longer has them to read.
            compose.onNodeWithText(listed(R.string.settings_key_name_exa, exaKey)).assertIsDisplayed()
            compose.onNodeWithText(listed(R.string.settings_key_name_lastfm, lastFmKey)).assertIsDisplayed()
            assertTrue(allAbsent())

            compose.onNodeWithText(text(R.string.key_screen_done)).performClick()
            waitFor("the screen and the row gone") { !shown(text(R.string.settings_remove_all_keys)) }
        }
    }

    @Test
    fun moreAboutSafeDeleteUnfoldsWhatTheDeletionDoes() {
        runBlocking { secrets.set(SecretId.EXA, Secret(exaKey)) }
        show {
            openPurge()
            val more = compose.onNodeWithContentDescription(text(R.string.purge_keys_more))
            more.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    text(R.string.settings_section_collapsed),
                ),
            )
            assertTrue(!shown(text(R.string.purge_keys_more_explained)))

            more.performClick()
            waitForText(text(R.string.purge_keys_more_explained))
            more.assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    text(R.string.settings_section_expanded),
                ),
            )
        }
    }

    @Test
    fun aPurgeWhosePhoneKeyCannotBeDeletedSaysSoAndTryAgainFinishesIt() {
        // The phone's key cannot be deleted the first time.
        val failingOnce =
            object : SoftwareKeySource() {
                var failures = 1

                override fun delete() {
                    if (failures-- > 0) throw ProviderException("key store down")
                    super.delete()
                }
            }
        val failingSecrets = SecretStore(stores.open("failing-secrets"), SecretCipher(failingOnce))
        runBlocking { failingSecrets.set(SecretId.LASTFM, Secret(lastFmKey)) }
        show(secretStore = failingSecrets) {
            openPurge()
            waitForText(listed(R.string.settings_key_name_lastfm, lastFmKey))
            compose.onNodeWithText(text(R.string.purge_keys_delete)).performClick()

            // The API keys are gone; only the phone's key that sealed them is left, and it says so.
            waitForText(text(R.string.purge_keys_seal_not_deleted))
            assertTrue(allAbsent(failingSecrets))
            assertTrue(!shown(text(R.string.purge_keys_not_removed)))
            assertTrue(!shown(listed(R.string.settings_key_name_lastfm, lastFmKey)))

            compose.onNodeWithText(text(R.string.purge_keys_try_again)).performClick()
            waitForText(text(R.string.purge_keys_done_title))
            assertEquals(1, failingOnce.deletions)
            // The confirmation lists the keys as they were before the first try.
            compose.onNodeWithText(listed(R.string.settings_key_name_lastfm, lastFmKey)).assertIsDisplayed()
        }
    }

    @Test
    fun aPurgeWhoseKeysCannotBeRemovedListsThemAndTryAgainFinishesIt() {
        val flaky = FlakyDataStore(stores.open("flaky-secrets"))
        val flakySecrets = SecretStore(flaky, testCipher())
        runBlocking { flakySecrets.set(SecretId.LASTFM, Secret(lastFmKey)) }
        show(secretStore = flakySecrets) {
            openPurge()
            waitForText(listed(R.string.settings_key_name_lastfm, lastFmKey))
            flaky.writeFailures.set(1)
            compose.onNodeWithText(text(R.string.purge_keys_delete)).performClick()

            // The entry stays, and, its phone's key deleted, can no longer be read: listed as such.
            waitForText(text(R.string.purge_keys_not_removed))
            val unreadable = app.getString(R.string.key_unreadable, text(R.string.settings_key_name_lastfm))
            waitForText(unreadable)
            assertTrue(!shown(text(R.string.purge_keys_seal_not_deleted)))

            compose.onNodeWithText(text(R.string.purge_keys_try_again)).performClick()
            waitForText(text(R.string.purge_keys_done_title))
            assertTrue(allAbsent(flakySecrets))
            compose.onNodeWithText(listed(R.string.settings_key_name_lastfm, lastFmKey)).assertIsDisplayed()
        }
    }

    @Test
    fun aPurgeWhoseStorageAndPhoneKeyFailAndWhoseFileCannotBeReadAfterListsTheKeysAsked() {
        val failingOnce =
            object : SoftwareKeySource() {
                var failures = 1

                override fun delete() {
                    if (failures-- > 0) throw ProviderException("key store down")
                    super.delete()
                }
            }
        val flaky = FlakyDataStore(stores.open("unread-secrets"))
        val failingSecrets = SecretStore(flaky, SecretCipher(failingOnce))
        runBlocking { failingSecrets.set(SecretId.LASTFM, Secret(lastFmKey)) }
        show(secretStore = failingSecrets) {
            openPurge()
            waitForText(listed(R.string.settings_key_name_lastfm, lastFmKey))
            // The edit fails, the Keystore key is not deleted, and the read after them fails, as
            // would any read after it: two failures, the reset's read and one more.
            flaky.writeFailures.set(1)
            flaky.failures.set(2)
            compose.onNodeWithText(text(R.string.purge_keys_delete)).performClick()

            // Unread, the key asked for is listed as it was, its last characters with it: the screen
            // reads nothing more, so the second failure is still to come.
            waitForText(text(R.string.purge_keys_both_failed))
            compose.onNodeWithText(listed(R.string.settings_key_name_lastfm, lastFmKey)).assertIsDisplayed()
            assertTrue(!shown(text(R.string.purge_keys_seal_not_deleted)))
            assertEquals(1, flaky.failures.get())
            flaky.failures.set(0)

            compose.onNodeWithText(text(R.string.purge_keys_try_again)).performClick()
            waitForText(text(R.string.purge_keys_done_title))
            assertTrue(allAbsent(failingSecrets))
        }
    }

    /** A key store whose next [failures] deletions fail. */
    private class FailingDeletes : SoftwareKeySource() {
        var failures = 0

        override fun delete() {
            if (failures > 0) {
                failures--
                throw ProviderException("key store down")
            }
            super.delete()
        }
    }

    @Test
    fun aRetryAfterTheKeysWereRemovedDoesNotListThemAgain() {
        val keys = FailingDeletes()
        val flaky = FlakyDataStore(stores.open("retry-removed-secrets"))
        val failingSecrets = SecretStore(flaky, SecretCipher(keys))
        runBlocking { failingSecrets.set(SecretId.LASTFM, Secret(lastFmKey)) }
        show(secretStore = failingSecrets) {
            openPurge()
            val lastFm = listed(R.string.settings_key_name_lastfm, lastFmKey)
            waitForText(lastFm)
            // The first try removes the entries; the Keystore key is not deleted.
            keys.failures = 1
            compose.onNodeWithText(text(R.string.purge_keys_delete)).performClick()
            waitForText(text(R.string.purge_keys_seal_not_deleted))

            // The retry fails both steps and cannot read the file after them: it starts from what
            // the first try left, nothing.
            flaky.writeFailures.set(1)
            flaky.failures.set(1)
            keys.failures = 1
            compose.onNodeWithText(text(R.string.purge_keys_try_again)).performClick()
            waitForText(text(R.string.purge_keys_both_failed_none))
            assertTrue(!shown(lastFm))
            assertTrue(!shown(text(R.string.purge_keys_both_failed)))

            compose.onNodeWithText(text(R.string.purge_keys_try_again)).performClick()
            waitForText(text(R.string.purge_keys_done_title))
        }
    }

    @Test
    fun aRetryAfterThePhonesKeyWasDeletedListsTheKeysLeftAsUnreadable() {
        val keys = FailingDeletes()
        val flaky = FlakyDataStore(stores.open("retry-sealed-secrets"))
        val failingSecrets = SecretStore(flaky, SecretCipher(keys))
        runBlocking { failingSecrets.set(SecretId.LASTFM, Secret(lastFmKey)) }
        show(secretStore = failingSecrets) {
            openPurge()
            val lastFm = listed(R.string.settings_key_name_lastfm, lastFmKey)
            waitForText(lastFm)
            // The first try deletes the Keystore key; the entries stay, and no longer open.
            flaky.writeFailures.set(1)
            compose.onNodeWithText(text(R.string.purge_keys_delete)).performClick()
            waitForText(text(R.string.purge_keys_not_removed))
            val unreadable = app.getString(R.string.key_unreadable, text(R.string.settings_key_name_lastfm))
            waitForText(unreadable)

            // The retry fails both steps and cannot read the file after them: the key it starts
            // from is the one the first try left, which can no longer be read.
            flaky.writeFailures.set(1)
            flaky.failures.set(1)
            keys.failures = 1
            compose.onNodeWithText(text(R.string.purge_keys_try_again)).performClick()
            waitForText(text(R.string.purge_keys_both_failed))
            compose.onNodeWithText(unreadable).assertIsDisplayed()
            assertTrue(!shown(lastFm))

            compose.onNodeWithText(text(R.string.purge_keys_try_again)).performClick()
            waitForText(text(R.string.purge_keys_done_title))
            assertTrue(allAbsent(failingSecrets))
        }
    }

    @Test
    fun anUnreadableKeyMakesPurgeKeysSayWhyAndIsListedAsSuch() {
        val dataStore = stores.open("reset-secrets")
        runBlocking { SecretStore(dataStore, testCipher()).set(SecretId.LASTFM, Secret(lastFmKey)) }
        // Another key than the one that sealed it, as after the Keystore lost the first.
        val resetting = SecretStore(dataStore, testCipher())
        show(secretStore = resetting) {
            waitForText(text(R.string.settings_reset_keys_value))
            compose.onNodeWithText(text(R.string.settings_key_unreadable)).performScrollTo().assertIsDisplayed()
            // A key that cannot be read can still be removed, alone or with the others.
            val removeLastFm = app.getString(R.string.settings_key_remove, text(R.string.settings_key_name_lastfm))
            compose.onNodeWithContentDescription(removeLastFm).assertExists()

            openPurge()
            val unreadable = app.getString(R.string.key_unreadable, text(R.string.settings_key_name_lastfm))
            waitForText(unreadable)
            compose.onNodeWithText(text(R.string.purge_keys_delete)).performClick()
            waitForText(text(R.string.purge_keys_done_title))
            compose.onNodeWithText(unreadable).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.key_screen_done)).performClick()

            waitFor("Purge keys gone") { !shown(text(R.string.settings_remove_all_keys)) }
            assertTrue(allAbsent(resetting))
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

            waitForText(text(R.string.settings_reset_keys_value))
            openPurge()
            // No key was saved: the screen says what deleting still does.
            compose.onNodeWithText(text(R.string.purge_keys_none)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.purge_keys_delete)).performClick()
            waitForText(text(R.string.purge_keys_done_none))
            compose.onNodeWithText(text(R.string.key_screen_done)).performClick()
            waitFor("Purge keys no longer saying why") { !shown(text(R.string.settings_reset_keys_value)) }
            assertEquals(1, keys.deletions)
        }
    }

    @Test
    fun aResetThatEndsAfterItsScreenHasGoneStillClearsTheOffer() {
        val flaky = FlakyDataStore(stores.open("gated-reset-secrets"))
        val gatedSecrets = SecretStore(flaky, testCipher())
        val gate = CompletableDeferred<Unit>()
        // The settings screen's state, which outlives the purge's: *Purge keys* saying why after a
        // key store failure.
        var sealFailed by mutableStateOf(true)
        var screenShown by mutableStateOf(true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    if (screenShown) {
                        KeyDialogs(
                            SettingsDialog.PurgeKeys,
                            emptyMap(),
                            gatedSecrets,
                            saves,
                            scanner = { },
                            keyboard = { _, _, _, _ -> },
                            showPhoneName = false,
                            onSealFailed = { sealFailed = it },
                            onDismiss = {},
                        )
                    }
                }
            }
            flaky.writeGate = gate
            waitForText(text(R.string.purge_keys_delete))
            compose.onNodeWithText(text(R.string.purge_keys_delete)).performClick()
            waitFor("the purge's write started") { flaky.writes.get() == 1 }

            // The screen goes while the reset waits; the state that observes it stays.
            screenShown = false
            compose.waitForIdle()
            gate.complete(Unit)
            waitFor("Purge keys no longer saying why") { !sealFailed }
        }
    }
}
