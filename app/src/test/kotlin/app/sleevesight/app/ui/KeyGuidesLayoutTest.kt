package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.scan.QrScanner
import app.sleevesight.app.ui.settings.GUIDED_CAMERA_MIN_HEIGHT
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The guided scans (#541) in a short window (320 dp, shorter than a phone in landscape) with text at
 * twice its size: the camera's view
 * keeps a usable height and can be scrolled wholly onto the screen, the title and the steps scroll,
 * and *Cancel* stays on the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h320dp", fontScale = 2f)
class KeyGuidesLayoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun close() {
        saves.cancel()
        stores.close()
    }

    private fun text(id: Int) = app.getString(id)

    /** A camera's view that reads nothing, tagged to be measured. */
    private val camera: QrScanner = { Box(Modifier.fillMaxSize().testTag(CAMERA)) }

    private fun waitForNode(matcher: SemanticsMatcher) =
        compose.waitUntil(conditionDescription = matcher.description, timeoutMillis = 5_000) {
            compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }

    /** Opens Last.fm's key dialog and the guided scan of its [icon], whose screen shows [shown]. */
    private fun openGuide(
        icon: Int,
        shown: Int,
        check: () -> Unit,
    ) {
        val settings = SettingsStore(stores.open("settings"))
        val secrets = SecretStore(stores.open("secrets"), testCipher())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        saves,
                        scanner = camera,
                        keyboard = { _, _, _, _ -> },
                    ) { it }
                }
            }
            waitForNode(hasText(text(R.string.settings_title)))
            compose.openSection(SettingsSection.SECRETS)
            compose.onNodeWithText(text(R.string.settings_key_name_lastfm)).performScrollTo().performClick()
            compose.onNodeWithContentDescription(text(icon)).performClick()
            waitForNode(hasText(text(shown)))
            check()
        }
    }

    /**
     * The camera's view is at least [GUIDED_CAMERA_MIN_HEIGHT] tall, scrolls wholly onto the screen,
     * inside what scrolls and above *Cancel*, and *Cancel*, on the screen showing [shown], stays on it.
     */
    private fun cameraUsableAndCancelReachable(shown: Int) {
        val onScreen = hasAnyAncestor(hasAnyDescendant(hasText(text(shown))))
        val cancel = compose.onNode(hasText(text(R.string.settings_cancel)) and onScreen)
        compose.onNodeWithTag(CAMERA).performScrollTo()
        val view = compose.onNodeWithTag(CAMERA).fetchSemanticsNode()
        val least = GUIDED_CAMERA_MIN_HEIGHT.value * app.resources.displayMetrics.density
        assertTrue("camera ${view.size.height} px, at least $least px", view.size.height >= least - 1)

        val scrolled = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag(CAMERA))).fetchSemanticsNode()
        val camera = view.boundsInWindow
        val port = scrolled.boundsInWindow
        val below = cancel.fetchSemanticsNode().boundsInWindow
        // The bounds on the screen are clipped to what scrolls: the whole view shows only when they
        // are as tall as the view itself.
        assertTrue("camera shows $camera of ${view.size.height} px", camera.height >= view.size.height - 1)
        assertTrue("camera $camera within $port", camera.top >= port.top - 1 && camera.bottom <= port.bottom + 1)
        assertTrue("camera $camera above Cancel $below", camera.bottom <= below.top + 1)
        cancel.assertIsDisplayed()
    }

    @Test
    fun theQrScanKeepsItsCameraAndCancelInAShortWindowWithLargeText() =
        openGuide(R.string.settings_key_scan, R.string.key_scan_make) {
            cameraUsableAndCancelReachable(R.string.key_scan_make)
            // The steps under the camera are reached by scrolling.
            compose.onNodeWithText(text(R.string.load_keys_read)).performScrollTo().assertIsDisplayed()
        }

    @Test
    fun theKeyboardGuideKeepsItsCameraAndCancelInAShortWindowWithLargeText() =
        openGuide(R.string.settings_key_type, R.string.keyboard_explained) {
            cameraUsableAndCancelReachable(R.string.keyboard_explained)
            compose.onNodeWithText(text(R.string.keyboard_connect)).performScrollTo().assertIsDisplayed()
        }

    private companion object {
        const val CAMERA = "camera"
    }
}
