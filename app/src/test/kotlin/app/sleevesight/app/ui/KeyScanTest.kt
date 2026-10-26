package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.keyboard.KeyboardVectors
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.keyboard.ComputerKeyboard
import app.sleevesight.app.ui.keyboard.KeyboardStatus
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.keyName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Entering a key by QR code in its dialog on the settings screen (#268), with a scanner that needs
 * no camera, and by typing on a computer (#394), with a keyboard that needs no Bluetooth. Several
 * keys at once, *Load keys*, are [LoadKeysTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
class KeyScanTest {
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
        scanner: QrScanner,
        keyboard: ComputerKeyboard = { _, _, _, _ -> },
        block: ActivityScenario<MainActivity>.() -> Unit,
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
                        scanner = scanner,
                        keyboard = keyboard,
                    ) { it }
                }
            }
            waitForText(text(R.string.settings_title))
            // The keys' rows, *Load keys* and *Purge keys* are under *Secrets* (#444).
            compose.openSection(SettingsSection.SECRETS)
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

    /** A scanner that reads [text] at once, as the camera would on a code held up to it. */
    private fun scanning(text: String): QrScanner = { onScanned -> LaunchedEffect(Unit) { onScanned(text) } }

    /** Keyboard sessions started and not yet ended. */
    private val running = mutableListOf<Any>()

    /** A computer that types [text] as soon as the session starts. */
    private fun typing(text: String): ComputerKeyboard =
        { session, _, onText, onStop ->
            DisposableEffect(session) {
                running += session
                onDispose { running -= session }
            }
            LaunchedEffect(session) { onText(text) }
            KeyboardStatus(R.string.keyboard_connected, onStop)
        }

    private val anthropicKey = "sk-ant-api03-" + "a1B2_c3-D4".repeat(9)
    private val openAiKey = "sk-proj-" + "e5F6_g7-H8".repeat(15)
    private val googleKey = "AQ.Ab8RN6" + "Lq0_x-Yz.9".repeat(5)
    private val exaKey = "0c6d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f"
    private val lastFmKey = "0123456789abcdef0123456789abcdef"

    private fun stored(id: SecretId): String? = runBlocking { secrets.get(id) }?.reveal()

    @Test
    fun aScannedKeyFillsTheFieldAndIsSavedOnlyOnSave() =
        show(scanner = scanning("  $lastFmKey\n")) {
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(text(R.string.settings_key_scan)).performClick()
            waitForNode(hasText(text(R.string.settings_save)) and isEnabled())
            compose.waitForIdle()
            assertNull(stored(SecretId.LASTFM))

            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            waitFor("the scanned Last.fm key stored") { stored(SecretId.LASTFM) == lastFmKey }
        }

    @Test
    fun eachAiServicesKeyRowScansItsOwnKey() {
        val keys =
            listOf(SecretId.ANTHROPIC to anthropicKey, SecretId.OPENAI to openAiKey, SecretId.GOOGLE to googleKey)
        for ((id, key) in keys) {
            show(scanner = scanning(key)) {
                compose.onNodeWithText(text(keyName(id))).performScrollTo().performClick()
                compose.onNodeWithContentDescription(text(R.string.settings_key_scan)).performClick()
                waitForNode(hasText(text(R.string.settings_save)) and isEnabled())
                compose.onNodeWithText(text(R.string.settings_save)).performClick()
                waitFor("the scanned $id key stored") { stored(id) == key }
            }
        }
    }

    @Test
    fun anAnthropicKeyScannedIntoOpenAisFieldIsRefused() =
        show(scanner = scanning(anthropicKey)) {
            compose.onNodeWithText(text(R.string.settings_key_name_openai)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(text(R.string.settings_key_scan)).performClick()
            waitForText(text(R.string.settings_key_scan_shape))
            compose.onNodeWithText(text(R.string.settings_save)).assertIsNotEnabled()
        }

    @Test
    fun aScannedKeyOfAnotherFormIsRefusedWithTheReason() =
        show(scanner = scanning(exaKey)) {
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(text(R.string.settings_key_scan)).performClick()
            waitForText(text(R.string.settings_key_scan_shape))
            compose.onNodeWithText(text(R.string.settings_save)).assertIsNotEnabled()
        }

    @Test
    fun aSetupCodeOfSeveralKeysScannedIntoOneKeysFieldPointsToLoadKeys() =
        show(scanner = scanning("sleevesight-keys:v1\nlastfm=$lastFmKey\nexa=$exaKey")) {
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(text(R.string.settings_key_scan)).performClick()
            waitForText(text(R.string.settings_key_scan_setup))
            compose.onNodeWithText(text(R.string.settings_save)).assertIsNotEnabled()
        }

    @Test
    fun aKeyboardCodeScannedWithTheQrIconStartsTheSessionAndNotTheKeysCheck() =
        show(scanner = scanning(KeyboardVectors.freshCode()), keyboard = typing(lastFmKey)) {
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(text(R.string.settings_key_scan)).performClick()
            waitForText(text(R.string.keyboard_connected))
            assertEquals(1, running.size)
            val characters = compose.onAllNodesWithText(text(R.string.settings_key_scan_characters))
            assertEquals(0, characters.fetchSemanticsNodes().size)
            // While the session runs, neither icon starts another scan.
            compose.onNodeWithContentDescription(text(R.string.settings_key_scan)).assertIsNotEnabled()
            compose.onNodeWithContentDescription(text(R.string.settings_key_type)).assertIsNotEnabled()
            waitForNode(hasText(text(R.string.settings_save)) and isEnabled())
            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            waitFor("the typed Last.fm key stored") { stored(SecretId.LASTFM) == lastFmKey }
        }

    @Test
    fun textTypedOnAComputerFillsTheFieldAndIsSavedOnSave() =
        show(scanner = scanning(KeyboardVectors.freshCode()), keyboard = typing(" $lastFmKey")) {
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(text(R.string.settings_key_type)).performClick()
            waitForText(text(R.string.keyboard_connected))
            assertEquals(1, running.size)
            waitForNode(hasText(text(R.string.settings_save)) and isEnabled())
            assertNull(stored(SecretId.LASTFM))

            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            waitFor("the typed Last.fm key stored") { stored(SecretId.LASTFM) == lastFmKey }
            // Closing the dialog ended the session.
            waitFor("the session ended") { running.isEmpty() }
        }

    @Test
    fun stopEndsTheSessionAndKeepsWhatWasTyped() =
        show(scanner = scanning(KeyboardVectors.freshCode()), keyboard = typing(lastFmKey)) {
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(text(R.string.settings_key_type)).performClick()
            waitForText(text(R.string.keyboard_connected))
            compose.onNodeWithText(text(R.string.keyboard_stop)).performClick()
            waitForNode(hasContentDescription(text(R.string.settings_key_type)) and isEnabled())
            waitFor("the session ended") { running.isEmpty() }
            compose.onNodeWithText(text(R.string.settings_save)).assertIsEnabled()
        }

    @Test
    fun theSameCodeScannedAgainStartsNoSession() {
        val code = KeyboardVectors.freshCode()
        show(scanner = scanning(code), keyboard = typing(lastFmKey)) {
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(text(R.string.settings_key_type)).performClick()
            waitForText(text(R.string.keyboard_connected))
            compose.onNodeWithText(text(R.string.keyboard_stop)).performClick()
            waitForNode(hasContentDescription(text(R.string.settings_key_type)) and isEnabled())
            waitFor("the session ended") { running.isEmpty() }

            compose.onNodeWithContentDescription(text(R.string.settings_key_type)).performClick()
            waitForText(text(R.string.keyboard_code_used))
            assertEquals(0, running.size)
        }
    }

    @Test
    fun aCodeThatIsNotAKeyboardCodeStartsNoSession() =
        show(scanner = scanning(lastFmKey), keyboard = typing(lastFmKey)) {
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(text(R.string.settings_key_type)).performClick()
            waitForText(text(R.string.keyboard_not_code))
            assertEquals(0, running.size)
            compose.onNodeWithText(text(R.string.settings_save)).assertIsNotEnabled()
        }

    @Test
    fun aKeyboardCodeOfALaterVersionIsSaidToBeUnreadable() =
        show(scanner = scanning("sleevesight-keyboard:v2:whatever"), keyboard = typing(lastFmKey)) {
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(text(R.string.settings_key_type)).performClick()
            waitForText(text(R.string.keyboard_code_unreadable))
            assertEquals(0, running.size)
        }
}
