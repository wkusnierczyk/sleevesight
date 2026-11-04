package app.sleevesight.app.ui

import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.settings.API_KEYS_URL
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.Secret
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog

/**
 * *Load keys* (#268, #446, #765): its screen, with a scanner that needs no camera, past its intro
 * and words (#590, `LoadKeysWordsTest`). A clear code that is all good, partly good, of nothing
 * usable, not a setup code, or of a later version, each told under the camera; the keys to save,
 * saved only at ④'s *Complete*, and a save that fails.
 */
@RunWith(RobolectricTestRunner::class)
class LoadKeysTest {
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

    /**
     * Opens *Load keys* on the settings screen, scanning with [scanner], and goes past its intro and
     * words to the camera.
     */
    private fun show(
        scanner: QrScanner,
        secretStore: SecretStore = secrets,
        block: ActivityScenario<MainActivity>.() -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secretStore,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        saves,
                        scanner = scanner,
                    ) { it }
                }
            }
            waitForText(text(R.string.settings_title))
            compose.openSection(SettingsSection.SECRETS)
            compose.onNodeWithText(text(R.string.settings_scan_keys)).performScrollTo().performClick()
            waitForText(text(R.string.load_keys_start))
            compose.onNodeWithText(text(R.string.load_keys_start)).performClick()
            waitForText(text(R.string.load_keys_next))
            compose.onNodeWithText(text(R.string.load_keys_next)).performClick()
            waitForText(scanHow)
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

    private fun ComposeTestRule.onAllNodesWithSubstring(text: String) =
        onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes()

    /**
     * A scanner that reads [codes] one at a time, each at once when it shows, as the camera would on
     * a code held up to it; once they are all read, it reads nothing more.
     */
    private fun scanning(vararg codes: String): QrScanner {
        val left = ArrayDeque(codes.toList())
        return { onScanned -> LaunchedEffect(Unit) { left.removeFirstOrNull()?.let(onScanned) } }
    }

    // The code [held] reads next, once set: read as the camera's step shows, or as it is set while
    // that step shows, as the camera reads on after a code it refused.
    private var code by mutableStateOf<String?>(null)
    private val held: QrScanner = { onScanned ->
        val next = code
        LaunchedEffect(next) {
            if (next != null) {
                code = null
                onScanned(next)
            }
        }
    }

    /** A scanner that never reads a code: the camera's step stays. */
    private val notScanning: QrScanner = { }

    private val anthropicKey = "sk-ant-api03-" + "a1B2_c3-D4".repeat(9)
    private val exaKey = "0c6d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f"
    private val lastFmKey = "0123456789abcdef0123456789abcdef"

    private fun stored(id: SecretId): String? = runBlocking { secrets.get(id) }?.reveal()

    private fun ending(key: String) = key.takeLast(4)

    private val scanHow by lazy { text(R.string.load_keys_scan_how) }

    /** A numbered step's title, as a screen reader reads it. */
    private fun step(
        number: Int,
        title: Int,
    ) = app.getString(R.string.load_keys_step, number, text(title))

    /** What a screen reader says of the row of the key [name] in [group] on the keys to save, its [note]. */
    private fun row(
        name: Int,
        group: Int,
        key: String,
        note: Int,
    ) = "${text(name)}, ${text(group)}, ending ${ending(key)}, ${text(note)}"

    /** What the summary says of the saved key [id] whose value is [key]: [saved], new or replaced. */
    private fun saved(
        id: Int,
        key: String,
        saved: Int = R.string.key_saved_new,
    ) = "${text(id)}, ending ${ending(key)}, ${text(saved)}"

    private val questions =
        listOf(R.string.load_keys_save_question, R.string.load_keys_save_question_one, R.string.load_keys_save_none)

    private fun closed() = !shown(scanHow) && questions.none { shown(text(it)) }

    /** From the camera, a code read, to the keys to save. */
    private fun toKeys() {
        waitForText(text(R.string.load_keys_read_title))
        compose.onNodeWithText(text(R.string.load_keys_next)).performClick()
        waitForText(text(R.string.load_keys_only_selected))
    }

    /** From the keys to save to the services to enable. */
    private fun toServices() {
        compose.onNodeWithText(text(R.string.load_keys_next)).performClick()
        waitForText(text(R.string.load_keys_enable_question))
    }

    private fun complete() = compose.onNodeWithText(text(R.string.load_keys_complete)).performClick()

    @Test
    fun theCameraStepIsNumberedSaysHowToScanAndHasNoLink() =
        show(scanner = notScanning) {
            compose.onNode(hasText(step(2, R.string.load_keys_scan_title)) and isHeading()).assertExists()
            compose.onNodeWithText(text(R.string.load_keys_scan_next)).performScrollTo().assertIsDisplayed()
            // *Next* is dimmed until a code is read; *Previous* goes back to the words.
            compose.onNodeWithText(text(R.string.load_keys_next)).assertIsNotEnabled()
            // The address is the intro's; none on this screen (the settings under it have theirs).
            val onScreen = hasAnyAncestor(hasAnyDescendant(hasText(scanHow)))
            val address = hasText(API_KEYS_URL, substring = true) and onScreen
            assertTrue(compose.onAllNodes(address, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithText(text(R.string.load_keys_previous)).performClick()
            waitForText(text(R.string.load_keys_words_secret))
        }

    @Test
    fun theScreenIsSecureAndCancelClosesIt() =
        show(scanner = notScanning) {
            val flags =
                ShadowDialog
                    .getLatestDialog()
                    .window!!
                    .attributes.flags
            assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)

            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()
            waitFor("the screen closed") { closed() }
        }

    @Test
    fun aGoodCodeShowsWhatItHoldsSavesNothingBeforeCompleteAndThenSaysWhatWasSaved() {
        runBlocking { secrets.set(SecretId.EXA, Secret("0c6d1e2f-0000-0000-0000-9a0b1c2d3e4f")) }
        show(scanner = scanning("sleevesight-keys:v1\nanthropic=$anthropicKey\nexa=$exaKey")) {
            waitForText(text(R.string.load_keys_read_title))
            compose.onNodeWithText(text(R.string.load_keys_read_done)).assertExists()
            // A clear code, not sealed with the words: a warning that anyone who saw it can read the keys.
            compose.onNodeWithText(text(R.string.load_keys_clear_title)).assertExists()
            compose.onNodeWithText(text(R.string.load_keys_clear)).assertExists()
            // The outcome only: no key is shown, nor its ending.
            assertEquals(0, compose.onAllNodesWithSubstring(ending(anthropicKey)).size)
            toKeys()

            compose.onNode(hasText(step(3, R.string.load_keys_keys_title)) and isHeading()).assertExists()
            compose.onNodeWithText(text(R.string.load_keys_save_question)).assertIsDisplayed()
            val anthropic =
                row(
                    R.string.settings_ai_provider_anthropic,
                    R.string.key_group_ai,
                    anthropicKey,
                    R.string.key_save_as_new,
                )
            compose.onNodeWithText(anthropic).assertIsDisplayed()
            val exa =
                row(
                    R.string.settings_engine_exa,
                    R.string.key_group_search,
                    exaKey,
                    R.string.key_replace_existing,
                )
            compose.onNodeWithText(exa).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.load_keys_only_selected)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.setup_explained)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.load_keys_clear_code)).performScrollTo().assertIsDisplayed()
            // All good: *Previous*, *Next* or *Cancel*, nothing else.
            assertTrue(!shown(text(R.string.setup_scan_again)) && !shown(text(R.string.load_keys_not_scanned)))
            compose.onNodeWithText(text(R.string.load_keys_previous)).assertIsDisplayed()
            // No key is shown whole, and nothing is saved, on ③ nor on ④.
            assertEquals(0, compose.onAllNodesWithSubstring(anthropicKey).size)
            assertEquals(0, compose.onAllNodesWithSubstring(exaKey).size)
            toServices()
            compose.onNode(hasText(step(4, R.string.load_keys_enable_title)) and isHeading()).assertExists()
            compose.waitForIdle()
            assertNull(stored(SecretId.ANTHROPIC))
            assertEquals("0c6d1e2f-0000-0000-0000-9a0b1c2d3e4f", stored(SecretId.EXA))

            complete()
            waitForText(text(R.string.load_keys_saved_title))
            assertEquals(anthropicKey, stored(SecretId.ANTHROPIC))
            assertEquals(exaKey, stored(SecretId.EXA))
            compose.onNode(hasText(text(R.string.load_keys_summary_title)) and isHeading()).assertExists()
            compose.onNodeWithText(saved(R.string.settings_ai_provider_anthropic, anthropicKey)).assertIsDisplayed()
            compose
                .onNodeWithText(saved(R.string.settings_engine_exa, exaKey, R.string.key_saved_replaced))
                .assertIsDisplayed()

            compose.onNodeWithText(text(R.string.key_screen_done)).performClick()
            waitFor("the screen closed") { !shown(text(R.string.load_keys_saved_title)) }
        }
    }

    @Test
    fun aPartlyGoodCodeListsTheKeysThatDidNotScanAndSavesTheGoodKeysOnly() =
        show(
            scanner = scanning("sleevesight-keys:v1\nanthropic=$anthropicKey\nlastfm=not-a-key\nmistral=x\nno equals"),
        ) {
            toKeys()
            waitForText(text(R.string.load_keys_not_scanned))
            // Last.fm's by its name and ending; the unknown name and the unreadable line as unknown keys.
            compose
                .onNodeWithText(
                    "\u2022\u00A0${text(R.string.settings_source_lastfm)}, ending -key",
                ).assertIsDisplayed()
            val unknown = app.resources.getQuantityString(R.plurals.load_keys_unknown_keys, 2, 2)
            compose.onNodeWithText("\u2022\u00A0$unknown").performScrollTo().assertIsDisplayed()
            // One key read: the heading asks of it alone; *Scan again* as a key did not scan.
            compose.onNodeWithText(text(R.string.load_keys_save_question_one)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.setup_scan_again)).assertIsDisplayed()
            assertTrue(!shown(text(R.string.load_keys_previous)))
            compose.onNodeWithText(text(R.string.settings_cancel)).assertIsDisplayed()

            toServices()
            complete()
            waitForText(text(R.string.load_keys_saved_title_one))
            assertEquals(anthropicKey, stored(SecretId.ANTHROPIC))
            assertNull(stored(SecretId.LASTFM))
        }

    @Test
    fun scanAgainReturnsToTheCameraAndReadsTheNextCode() =
        show(scanner = scanning("sleevesight-keys:v1\nlastfm=not-a-key", "sleevesight-keys:v1\nlastfm=$lastFmKey")) {
            // Nothing usable: no table and no *Next*, only *Scan again* or *Cancel*.
            waitForText(text(R.string.load_keys_read_title))
            compose.onNodeWithText(text(R.string.load_keys_next)).performClick()
            waitForText(text(R.string.load_keys_save_none))
            compose.onNodeWithText(text(R.string.load_keys_not_scanned)).assertIsDisplayed()
            assertTrue(!shown(text(R.string.load_keys_next)) && !shown(text(R.string.key_table_save)))

            compose.onNodeWithText(text(R.string.setup_scan_again)).performClick()
            toKeys()
            compose
                .onNodeWithText(
                    row(R.string.settings_source_lastfm, R.string.key_group_music, lastFmKey, R.string.key_save_as_new),
                ).assertIsDisplayed()
            toServices()
            complete()
            waitFor("the Last.fm key stored") { stored(SecretId.LASTFM) == lastFmKey }
        }

    @Test
    fun aCodeThatIsNotASetupCodeSaysSoAndTheCameraReadsTheNext() =
        show(scanner = held) {
            code = lastFmKey
            waitForText(text(R.string.load_keys_not_a_setup_title))
            compose.onNodeWithText(text(R.string.load_keys_not_a_setup)).assertExists()
            compose.onNodeWithText(text(R.string.load_keys_next)).assertIsNotEnabled()
            assertTrue(!shown(text(R.string.setup_scan_again)))

            code = "sleevesight-keys:v1\nlastfm=$lastFmKey"
            waitForText(text(R.string.load_keys_read_title))
            assertTrue(!shown(text(R.string.load_keys_not_a_setup_title)))
            compose.onNodeWithText(text(R.string.load_keys_next)).assertIsEnabled()
        }

    @Test
    fun aCodeOfALaterVersionAsksForAnUpdate() =
        show(scanner = scanning("sleevesight-keys:v3\nlastfm=$lastFmKey")) {
            waitForText(text(R.string.load_keys_newer_title))
            compose.onNodeWithText(text(R.string.load_keys_newer)).assertExists()
            compose.onNodeWithText(text(R.string.load_keys_next)).assertIsNotEnabled()
            assertNull(stored(SecretId.LASTFM))
        }

    @Test
    fun cancelOnWhatTheCodeHoldsSavesNothing() =
        show(scanner = scanning("sleevesight-keys:v1\nlastfm=$lastFmKey")) {
            toKeys()
            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()
            waitFor("the screen closed") { closed() }
            assertNull(stored(SecretId.LASTFM))
        }

    @Test
    fun aKeyThatCannotBeSavedIsNamedAndSavedAloneOnTheNextTry() {
        val flaky = FlakyDataStore(stores.open("flaky-secrets"))
        val store = SecretStore(flaky, testCipher())
        show(
            scanner = scanning("sleevesight-keys:v1\nanthropic=$anthropicKey\nlastfm=$lastFmKey"),
            secretStore = store,
        ) {
            toKeys()
            toServices()
            flaky.writeFailures.set(1)
            complete()

            // The first key failed; the second was saved; ④ stays, naming the one left.
            val failed = app.getString(R.string.setup_failed, text(R.string.settings_key_name_anthropic))
            waitForText(failed)
            assertEquals(lastFmKey, runBlocking { store.get(SecretId.LASTFM) }?.reveal())
            assertNull(runBlocking { store.get(SecretId.ANTHROPIC) })
            // The save has reached the screen first: *Secrets*, under it, offers to remove the key.
            val lastFm = text(R.string.settings_key_name_lastfm)
            waitForNode(hasContentDescription(app.getString(R.string.settings_key_remove, lastFm)))

            // Back on ③, the key saved still reads as new, as it was when the code was read, and
            // stays ticked: it cannot be unticked.
            compose.onNodeWithText(text(R.string.load_keys_previous)).performClick()
            val lastFmRow =
                row(R.string.settings_source_lastfm, R.string.key_group_music, lastFmKey, R.string.key_save_as_new)
            waitForText(lastFmRow)
            compose.onNodeWithText(lastFmRow).assertIsOn().assertIsNotEnabled()
            toServices()

            val writes = flaky.writes.get()
            complete()
            waitForText(text(R.string.load_keys_saved_title))
            assertEquals(anthropicKey, runBlocking { store.get(SecretId.ANTHROPIC) }?.reveal())
            // Only the key left was saved again, and the summary names both.
            assertEquals(writes + 1, flaky.writes.get())
            compose.onNodeWithText(saved(R.string.settings_ai_provider_anthropic, anthropicKey)).assertIsDisplayed()
            compose.onNodeWithText(saved(R.string.settings_source_lastfm, lastFmKey)).assertIsDisplayed()
        }
    }
}
