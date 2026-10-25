package app.sleevesight.app.ui

import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.scan.QrScanner
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * *Load keys* (#268, #446): its screen, with a scanner that needs no camera. A code that is all
 * good, partly good, of nothing usable, not a setup code, or of a later version; saving, and a save
 * that fails.
 */
@RunWith(RobolectricTestRunner::class)
class LoadKeysTest {
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

    /** Opens *Load keys* on the settings screen, scanning with [scanner]. */
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
            scenario.block()
        }
    }

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) = compose.waitUntil(conditionDescription = what, timeoutMillis = 5_000) { condition() }

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

    /** A scanner that never reads a code: the camera's step stays. */
    private val notScanning: QrScanner = { }

    private val anthropicKey = "sk-ant-api03-" + "a1B2_c3-D4".repeat(9)
    private val exaKey = "0c6d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f"
    private val lastFmKey = "0123456789abcdef0123456789abcdef"

    private fun stored(id: SecretId): String? = runBlocking { secrets.get(id) }?.reveal()

    private fun ending(key: String) = "…" + key.takeLast(4)

    /** What the confirmation says of the saved key [id] whose value is [key]. */
    private fun saved(
        id: Int,
        key: String,
    ) = app.getString(R.string.key_ending, text(id), ending(key))

    private fun closed() = !shown(text(R.string.load_keys_read)) && !shown(text(R.string.setup_title))

    @Test
    fun theScreenSaysHowToMakeTheCodeAndOpensTheInstructions() =
        show(scanner = notScanning) {
            waitForText(text(R.string.load_keys_make))
            compose.onNodeWithText(text(R.string.load_keys_read)).assertIsDisplayed()
            // keys-qr is for developers: not named outside developer mode.
            assertTrue(!shown(text(R.string.load_keys_developers)))

            val address = "sleevesight.app/api-keys"
            compose
                .onNodeWithContentDescription(
                    app.getString(R.string.load_keys_instructions_of, address),
                ).performClick()
            var started: Intent? = null
            onActivity { started = shadowOf(it).nextStartedActivity }
            assertEquals(Intent.ACTION_VIEW, started?.action)
            assertEquals("https://$address", started?.dataString)
        }

    @Test
    fun inDeveloperModeTheScreenNamesKeysQr() {
        runBlocking { settings.update { it.copy(developerMode = true) } }
        show(scanner = notScanning) {
            waitForText(text(R.string.load_keys_developers))
        }
    }

    @Test
    fun theScreenIsSecureAndCancelClosesIt() =
        show(scanner = notScanning) {
            waitForText(text(R.string.load_keys_read))
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
    fun aGoodCodeShowsWhatItHoldsSavesNothingBeforeSaveAndThenSaysWhatWasSaved() {
        runBlocking { secrets.set(SecretId.EXA, Secret("0c6d1e2f-0000-0000-0000-9a0b1c2d3e4f")) }
        show(scanner = scanning("sleevesight-keys:v1\nanthropic=$anthropicKey\nexa=$exaKey")) {
            val anthropic = text(R.string.settings_key_name_anthropic)
            val exa = text(R.string.settings_key_name_exa)
            waitForText(app.getString(R.string.setup_new, anthropic, ending(anthropicKey)))
            compose.onNodeWithText(app.getString(R.string.setup_replaces, exa, ending(exaKey))).assertIsDisplayed()
            // All good: *Save* or *Cancel*, nothing else.
            for (absent in listOf(
                R.string.setup_scan_again,
                R.string.load_keys_save_these,
                R.string.load_keys_problems,
            )) {
                assertTrue(!shown(text(absent)))
            }
            // No key is shown whole, and nothing is saved yet.
            assertEquals(0, compose.onAllNodesWithSubstring(anthropicKey).size)
            assertEquals(0, compose.onAllNodesWithSubstring(exaKey).size)
            compose.waitForIdle()
            assertNull(stored(SecretId.ANTHROPIC))

            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            waitForText(text(R.string.load_keys_saved_title))
            assertEquals(anthropicKey, stored(SecretId.ANTHROPIC))
            assertEquals(exaKey, stored(SecretId.EXA))
            compose.onNodeWithText(saved(R.string.settings_key_name_anthropic, anthropicKey)).assertIsDisplayed()
            compose.onNodeWithText(saved(R.string.settings_key_name_exa, exaKey)).assertIsDisplayed()

            compose.onNodeWithText(text(R.string.key_screen_done)).performClick()
            waitFor("the screen closed") { !shown(text(R.string.load_keys_saved_title)) }
        }
    }

    @Test
    fun aPartlyGoodCodeNamesEveryProblemAndSavesTheGoodKeysOnly() =
        show(
            scanner = scanning("sleevesight-keys:v1\nanthropic=$anthropicKey\nlastfm=not-a-key\nmistral=x\nno equals"),
        ) {
            val lastFm = text(R.string.settings_key_name_lastfm)
            waitForText(text(R.string.load_keys_problems))
            compose
                .onNodeWithText(app.getString(R.string.setup_refused, lastFm, text(R.string.setup_refused_shape)))
                .performScrollTo()
                .assertIsDisplayed()
            compose
                .onNodeWithText(
                    app.getString(R.string.setup_unknown, "mistral"),
                ).performScrollTo()
                .assertIsDisplayed()
            val unreadable = app.resources.getQuantityString(R.plurals.setup_unreadable, 1, 1)
            compose.onNodeWithText(unreadable).performScrollTo().assertIsDisplayed()
            // Partial: *Save these*, *Scan again* or *Cancel*.
            compose.onNodeWithText(text(R.string.setup_scan_again)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.settings_cancel)).assertIsDisplayed()
            assertTrue(!shown(text(R.string.settings_save)))

            compose.onNodeWithText(text(R.string.load_keys_save_these)).performClick()
            waitForText(text(R.string.load_keys_saved_title))
            assertEquals(anthropicKey, stored(SecretId.ANTHROPIC))
            assertNull(stored(SecretId.LASTFM))
        }

    @Test
    fun scanAgainReturnsToTheCameraAndReadsTheNextCode() =
        show(scanner = scanning("sleevesight-keys:v1\nlastfm=not-a-key", "sleevesight-keys:v1\nlastfm=$lastFmKey")) {
            // Nothing usable: *Scan again* or *Cancel*, no save.
            waitForText(text(R.string.load_keys_nothing_title))
            compose.onNodeWithText(text(R.string.setup_nothing)).assertIsDisplayed()
            assertTrue(!shown(text(R.string.settings_save)) && !shown(text(R.string.load_keys_save_these)))

            compose.onNodeWithText(text(R.string.setup_scan_again)).performClick()
            waitForText(app.getString(R.string.setup_new, text(R.string.settings_key_name_lastfm), ending(lastFmKey)))
            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            waitFor("the Last.fm key stored") { stored(SecretId.LASTFM) == lastFmKey }
        }

    @Test
    fun aCodeThatIsNotASetupCodeSaysSoAndOffersScanAgain() =
        show(scanner = scanning(lastFmKey)) {
            waitForText(text(R.string.setup_not_a_setup_title))
            compose.onNodeWithText(text(R.string.setup_not_a_setup)).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.setup_scan_again)).assertIsDisplayed()
            assertTrue(!shown(text(R.string.settings_save)))
        }

    @Test
    fun aCodeOfALaterVersionAsksForAnUpdate() =
        show(scanner = scanning("sleevesight-keys:v2\nlastfm=$lastFmKey")) {
            waitForText(text(R.string.setup_unsupported_title))
            compose.onNodeWithText(app.getString(R.string.setup_unsupported, "v2")).assertIsDisplayed()
            compose.onNodeWithText(text(R.string.setup_scan_again)).assertIsDisplayed()
            assertTrue(!shown(text(R.string.settings_save)))
            assertNull(stored(SecretId.LASTFM))
        }

    @Test
    fun cancelOnWhatTheCodeHoldsSavesNothing() =
        show(scanner = scanning("sleevesight-keys:v1\nlastfm=$lastFmKey")) {
            waitForText(text(R.string.setup_title))
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
            waitForText(text(R.string.setup_title))
            flaky.writeFailures.set(1)
            compose.onNodeWithText(text(R.string.settings_save)).performClick()

            // The first key failed; the second was saved; the screen stays, naming the one left.
            waitForText(app.getString(R.string.setup_failed, text(R.string.settings_key_name_anthropic)))
            assertEquals(lastFmKey, runBlocking { store.get(SecretId.LASTFM) }?.reveal())
            assertNull(runBlocking { store.get(SecretId.ANTHROPIC) })
            // The key saved on the first try still reads as new: as it was when the code was read.
            val lastFm = text(R.string.settings_key_name_lastfm)
            compose.onNodeWithText(app.getString(R.string.setup_new, lastFm, ending(lastFmKey))).assertIsDisplayed()

            val writes = flaky.writes.get()
            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            waitForText(text(R.string.load_keys_saved_title))
            assertEquals(anthropicKey, runBlocking { store.get(SecretId.ANTHROPIC) }?.reveal())
            // Only the key left was saved again, and the confirmation names both.
            assertEquals(writes + 1, flaky.writes.get())
            compose.onNodeWithText(saved(R.string.settings_key_name_anthropic, anthropicKey)).assertIsDisplayed()
            compose.onNodeWithText(saved(R.string.settings_key_name_lastfm, lastFmKey)).assertIsDisplayed()
        }
    }
}
