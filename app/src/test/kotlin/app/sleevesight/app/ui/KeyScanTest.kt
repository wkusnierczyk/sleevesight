package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.settings.SettingsScreen
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Entering keys by QR code on the settings screen (#268), with a scanner that needs no camera. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
                    ) { it }
                }
            }
            waitForText(text(R.string.settings_title))
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

    private fun ComposeTestRule.onAllNodesWithSubstring(text: String) =
        onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes()

    /** A scanner that reads [text] at once, as the camera would on a code held up to it. */
    private fun scanning(text: String): QrScanner = { onScanned, _ -> LaunchedEffect(Unit) { onScanned(text) } }

    private val anthropicKey = "sk-ant-api03-" + "a1B2_c3-D4".repeat(9)
    private val exaKey = "0c6d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f"
    private val lastFmKey = "0123456789abcdef0123456789abcdef"

    private fun stored(id: SecretId): String? = runBlocking { secrets.get(id) }?.reveal()

    @Test
    fun aScannedKeyFillsTheFieldAndIsSavedOnlyOnSave() =
        show(scanner = scanning("  $lastFmKey\n")) {
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_key_scan)).performClick()
            waitForNode(hasText(text(R.string.settings_save)) and isEnabled())
            compose.waitForIdle()
            assertNull(stored(SecretId.LASTFM))

            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            waitFor("the scanned Last.fm key stored") { stored(SecretId.LASTFM) == lastFmKey }
        }

    @Test
    fun aScannedKeyOfAnotherFormIsRefusedWithTheReason() =
        show(scanner = scanning(exaKey)) {
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_key_scan)).performClick()
            waitForText(text(R.string.settings_key_scan_shape))
            compose.onNodeWithText(text(R.string.settings_save)).assertIsNotEnabled()
        }

    @Test
    fun aSetupCodeScannedIntoOneKeysFieldPointsToScanKeys() =
        show(scanner = scanning("sleevesight-keys:v1\nlastfm=$lastFmKey")) {
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.settings_key_scan)).performClick()
            waitForText(text(R.string.settings_key_scan_setup))
            compose.onNodeWithText(text(R.string.settings_save)).assertIsNotEnabled()
        }

    @Test
    fun scanKeysShowsWhatTheCodeHoldsAndSavesNothingBeforeTheConfirmation() {
        runBlocking { secrets.set(SecretId.EXA, Secret("0c6d1e2f-0000-0000-0000-9a0b1c2d3e4f")) }
        val code = "sleevesight-keys:v1\nanthropic=$anthropicKey\nexa=$exaKey\nlastfm=not-a-key\nopenai=sk-x"
        show(scanner = scanning(code)) {
            compose.onNodeWithText(text(R.string.settings_scan_keys)).performScrollTo().performClick()
            val anthropic = text(R.string.settings_key_name_anthropic)
            val exa = text(R.string.settings_key_name_exa)
            val lastFm = text(R.string.settings_key_name_lastfm)
            waitForText(app.getString(R.string.setup_new, anthropic, "…" + anthropicKey.takeLast(4)))
            compose
                .onNodeWithText(
                    app.getString(R.string.setup_replaces, exa, "…" + exaKey.takeLast(4)),
                ).assertIsDisplayed()
            compose
                .onNodeWithText(app.getString(R.string.setup_refused, lastFm, text(R.string.setup_refused_shape)))
                .performScrollTo()
                .assertIsDisplayed()
            compose
                .onNodeWithText(app.getString(R.string.setup_unknown, "openai"))
                .performScrollTo()
                .assertIsDisplayed()
            // No key is shown whole.
            assertEquals(0, compose.onAllNodesWithSubstring(anthropicKey).size)
            assertEquals(0, compose.onAllNodesWithSubstring(exaKey).size)
            // Nothing is saved yet.
            compose.waitForIdle()
            assertNull(stored(SecretId.ANTHROPIC))
            assertEquals("0c6d1e2f-0000-0000-0000-9a0b1c2d3e4f", stored(SecretId.EXA))

            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            waitFor("the setup's keys stored") {
                stored(SecretId.ANTHROPIC) == anthropicKey &&
                    stored(SecretId.EXA) == exaKey
            }
            assertNull(stored(SecretId.LASTFM))
            waitFor("the dialog closed") {
                compose.onAllNodesWithText(text(R.string.setup_title)).fetchSemanticsNodes().isEmpty()
            }
        }
    }

    @Test
    fun scanKeysCancelledSavesNothing() =
        show(scanner = scanning("sleevesight-keys:v1\nlastfm=$lastFmKey")) {
            compose.onNodeWithText(text(R.string.settings_scan_keys)).performScrollTo().performClick()
            waitForText(text(R.string.setup_title))
            compose.onNodeWithText(text(R.string.settings_cancel)).performClick()
            waitFor("the dialog closed") {
                compose.onAllNodesWithText(text(R.string.setup_title)).fetchSemanticsNodes().isEmpty()
            }
            assertNull(stored(SecretId.LASTFM))
        }

    @Test
    fun scanKeysSaysWhenTheCodeIsNotASetupCode() =
        show(scanner = scanning(lastFmKey)) {
            compose.onNodeWithText(text(R.string.settings_scan_keys)).performScrollTo().performClick()
            waitForText(text(R.string.setup_not_a_setup_title))
            compose.onNodeWithText(text(R.string.setup_scan_again)).assertIsDisplayed()
        }
}
