package app.sleevesight.app.ui

import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.robolectric.shadows.ShadowDialog

/**
 * Where a key's QR code comes from (#541): the description and links under *Load keys*, no CLI on
 * its screen, and the guided screens the key field's icons open, laid out as *Load keys*' is; and a
 * setup code of one key, as the API keys page makes it, scanned into that key's field.
 */
@RunWith(RobolectricTestRunner::class)
class KeyGuidesTest {
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

    /** The settings screen, *Secrets* open, scanning with [scanner]. */
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
                        keyboard = { _, _, _, _ -> },
                    ) { it }
                }
            }
            waitForText(text(R.string.settings_title))
            compose.openSection(SettingsSection.SECRETS)
            scenario.block()
        }
    }

    private fun waitForNode(matcher: SemanticsMatcher) =
        compose.waitUntil(conditionDescription = matcher.description, timeoutMillis = WAIT_MS) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitForText(text: String) = waitForNode(hasText(text))

    private fun shown(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** A scanner that reads [code] at once, as the camera would on a code held up to it. */
    private fun scanning(code: String): QrScanner = { onScanned -> LaunchedEffect(Unit) { onScanned(code) } }

    /** A scanner that never reads a code: the guided screen stays. */
    private val notScanning: QrScanner = { }

    private val lastFmKey = "0123456789abcdef0123456789abcdef"
    private val exaKey = "0c6d1e2f-3a4b-5c6d-7e8f-9a0b1c2d3e4f"

    private fun stored(id: SecretId): String? = runBlocking { secrets.get(id) }?.reveal()

    /** The page the activity was last asked to open. */
    private fun ActivityScenario<MainActivity>.opened(): Intent? {
        var started: Intent? = null
        onActivity { started = shadowOf(it).nextStartedActivity }
        return started
    }

    /** Whether the newest window, a key screen, is secure: no screenshot or recording shows it. */
    private fun latestWindowIsSecure(): Boolean =
        ShadowDialog
            .getLatestDialog()
            .window!!
            .attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    /** The [text] in the window that also shows [shownWith]: the guided screen's, not the key dialog's. */
    private fun inScreenWith(
        text: String,
        shownWith: String,
    ) = hasText(text) and hasAnyAncestor(hasAnyDescendant(hasText(shownWith)))

    private fun openKey(id: Int) = compose.onNodeWithText(text(id)).performScrollTo().performClick()

    @Test
    fun loadKeysSaysWhereTheCodeIsMadeAndLinksTheGuideToIt() =
        show(scanner = notScanning) {
            val more = hasText("sleevesight.app/api-keys", substring = true)
            waitForNode(more)
            val line =
                compose
                    .onNode(more)
                    .performScrollTo()
                    .assertIsDisplayed()
                    .fetchSemanticsNode()
                    .config[SemanticsProperties.Text]
                    .single()
            val docsName = text(R.string.settings_scan_keys_docs)
            val expected = app.getString(R.string.settings_scan_keys_more, "sleevesight.app/api-keys", docsName)
            assertEquals(expected, line.text)
            val docs = "https://sleevesight.app/docs/tutorials/settings/#several-keys-at-once-load-keys"
            assertEquals(
                listOf("https://sleevesight.app/api-keys", docs),
                line.getLinkAnnotations(0, line.length).map { (it.item as LinkAnnotation.Url).url },
            )

            compose.onNode(more).performFirstLinkClick { (it.item as LinkAnnotation.Url).url == docs }
            val guide = opened()
            assertEquals(Intent.ACTION_VIEW, guide?.action)
            assertEquals(docs, guide?.dataString)
            compose.onNode(more).performFirstLinkClick { (it.item as LinkAnnotation.Url).url.endsWith("/api-keys") }
            assertEquals("https://sleevesight.app/api-keys", opened()?.dataString)
            // The links are apart from the row, which still opens the screen.
            assertTrue(!shown(hasText(text(R.string.load_keys_type))))
        }

    @Test
    fun loadKeysNamesNoCommandLineToolEvenInDeveloperMode() {
        runBlocking { settings.update { it.copy(developerMode = true) } }
        show(scanner = notScanning) {
            compose.onNodeWithText(text(R.string.settings_scan_keys)).performScrollTo().performClick()
            waitForText(text(R.string.load_keys_type))
            assertTrue(!shown(hasText("keys-qr", substring = true)))
            compose.onNodeWithText(text(R.string.load_keys_next)).performClick()
            waitForText(text(R.string.load_keys_read_code))
            assertTrue(!shown(hasText("keys-qr", substring = true)))
        }
    }

    @Test
    fun theQrIconOpensASecureGuidedScreenThatSaysWhatCodeAndWhereToMakeIt() =
        show(scanner = notScanning) {
            openKey(R.string.settings_key_name_lastfm)
            compose.onNodeWithContentDescription(text(R.string.settings_key_scan)).performClick()
            val make = text(R.string.key_scan_make)
            waitForText(make)
            assertTrue(shown(hasText(text(R.string.settings_key_scan)) and isHeading()))
            assertTrue(shown(hasText(make) and isHeading()))
            assertTrue(shown(hasText(text(R.string.load_keys_read)) and isHeading()))
            assertTrue(latestWindowIsSecure())

            compose
                .onNodeWithContentDescription(
                    app.getString(R.string.load_keys_instructions_of, "sleevesight.app/api-keys"),
                ).performClick()
            assertEquals("https://sleevesight.app/api-keys", opened()?.dataString)

            // *Cancel* returns to the key's dialog, which stays open.
            compose.onNode(inScreenWith(text(R.string.settings_cancel), make)).performClick()
            compose.waitUntil(WAIT_MS) { !shown(hasText(make)) }
            compose.onNodeWithText(text(R.string.settings_key_field)).assertIsDisplayed()
        }

    @Test
    fun theKeyboardIconOpensASecureGuidedScreenThatExplainsTypingOnAComputer() =
        show(scanner = notScanning) {
            openKey(R.string.settings_key_name_lastfm)
            compose.onNodeWithContentDescription(text(R.string.settings_key_type)).performClick()
            val explained = text(R.string.keyboard_explained)
            waitForText(explained)
            assertTrue(shown(hasText(text(R.string.settings_key_type)) and isHeading()))
            for (step in listOf(R.string.keyboard_open, R.string.keyboard_scan, R.string.keyboard_connect)) {
                assertTrue(text(step), shown(hasText(text(step)) and isHeading()))
            }
            assertTrue(latestWindowIsSecure())

            compose
                .onNodeWithContentDescription(
                    app.getString(R.string.keyboard_page_of, "sleevesight.app/keyboard.html"),
                ).performClick()
            assertEquals("https://sleevesight.app/keyboard.html", opened()?.dataString)

            compose.onNode(inScreenWith(text(R.string.settings_cancel), explained)).performClick()
            compose.waitUntil(WAIT_MS) { !shown(hasText(explained)) }
            compose.onNodeWithText(text(R.string.settings_key_field)).assertIsDisplayed()
        }

    @Test
    fun aSetupCodeOfThisKeyAloneFillsItsField() =
        show(scanner = scanning("sleevesight-keys:v1\nlastfm=$lastFmKey")) {
            openKey(R.string.settings_key_name_lastfm)
            compose.onNodeWithContentDescription(text(R.string.settings_key_scan)).performClick()
            waitForNode(hasText(text(R.string.settings_save)) and isEnabled())
            compose.onNodeWithText(text(R.string.settings_save)).performClick()
            compose.waitUntil(WAIT_MS) { stored(SecretId.LASTFM) == lastFmKey }
        }

    @Test
    fun aSetupCodeOfAnotherServicesKeyAlonePointsToLoadKeys() =
        show(scanner = scanning("sleevesight-keys:v1\nexa=$exaKey")) {
            openKey(R.string.settings_key_name_lastfm)
            compose.onNodeWithContentDescription(text(R.string.settings_key_scan)).performClick()
            waitForText(text(R.string.settings_key_scan_setup))
        }

    @Test
    fun aSetupCodeOfThisKeyAloneThatIsRefusedSaysWhy() =
        show(scanner = scanning("sleevesight-keys:v1\nlastfm=not-a-key")) {
            openKey(R.string.settings_key_name_lastfm)
            compose.onNodeWithContentDescription(text(R.string.settings_key_scan)).performClick()
            waitForText(text(R.string.settings_key_scan_shape))
        }

    /** Scans [code] into Last.fm's field, and waits for [message]; the field stays empty. */
    private fun scannedSays(
        code: String,
        message: Int,
    ) = show(scanner = scanning(code)) {
        openKey(R.string.settings_key_name_lastfm)
        compose.onNodeWithContentDescription(text(R.string.settings_key_scan)).performClick()
        waitForText(text(message))
        compose.onNodeWithText(text(R.string.settings_save)).assertIsNotEnabled()
    }

    @Test
    fun aSetupCodeOfThisKeyTwiceSaysSoAndLeavesTheFieldEmpty() =
        scannedSays("sleevesight-keys:v1\nlastfm=$lastFmKey\nlastfm=$lastFmKey", R.string.settings_key_scan_duplicate)

    @Test
    fun aSetupCodeOfThisKeyAndAnUnknownServiceLeavesTheFieldEmpty() =
        scannedSays("sleevesight-keys:v1\nlastfm=$lastFmKey\nmistral=abc", R.string.settings_key_scan_setup_more)

    @Test
    fun aSetupCodeOfThisKeyAndAnUnreadableLineLeavesTheFieldEmpty() =
        scannedSays("sleevesight-keys:v1\nlastfm=$lastFmKey\nno equals", R.string.settings_key_scan_setup_more)

    @Test
    fun aSetupCodeOfOnlyAnUnknownServiceSaysNoKeyCanBeRead() =
        scannedSays("sleevesight-keys:v1\nmistral=abc", R.string.settings_key_scan_setup_none)

    @Test
    fun aSetupCodeOfALaterVersionAsksForAnUpdate() =
        scannedSays("sleevesight-keys:v3\nlastfm=$lastFmKey", R.string.settings_key_scan_setup_newer)

    @Test
    fun aSealedSetupCodePointsToLoadKeys() =
        scannedSays(
            "sleevesight-keys:v2:AAknwBAXHiUsMzpBSE9WXWRrcnnAzdrn9AEOGyg1Qk9O",
            R.string.settings_key_scan_setup_sealed,
        )

    @Test
    fun aSetupCodeOfNoKeysSaysSo() = scannedSays("sleevesight-keys:v1\n", R.string.settings_key_scan_setup_empty)

    @Test
    fun aRefusedKeyBesideAnUnknownLineSaysWhyTheKeyIsRefused() =
        scannedSays("sleevesight-keys:v1\nlastfm=not-a-key\nmistral=abc", R.string.settings_key_scan_shape)

    @Test
    fun aScanCancelledLeavesWhatWasTypedInTheField() =
        show(scanner = notScanning) {
            openKey(R.string.settings_key_name_lastfm)
            compose.onNodeWithText(text(R.string.settings_key_field)).performTextInput(lastFmKey)
            compose.onNodeWithContentDescription(text(R.string.settings_key_scan)).performClick()
            val make = text(R.string.key_scan_make)
            waitForText(make)
            compose.onNode(inScreenWith(text(R.string.settings_cancel), make)).performClick()
            compose.waitUntil(WAIT_MS) { !shown(hasText(make)) }

            compose.onNodeWithText(text(R.string.settings_save)).assertIsEnabled().performClick()
            compose.waitUntil(WAIT_MS) { stored(SecretId.LASTFM) == lastFmKey }
        }
}
