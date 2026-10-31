package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.BuildConfig
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.settings.VERSION_TAG
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/**
 * Developer mode (#444): seven taps on *About*'s *Version* show the *Developer* section, whose
 * *Developer mode* switch hides it again and turns its options off; *Diagnostics*, the developer
 * setting, with the way to the diagnostics screen behind it (#54); *Show grounding details* (#437);
 * and the players' package names, shown only in developer mode.
 */
@RunWith(RobolectricTestRunner::class)
class DeveloperSettingsTest {
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

    private fun shown(id: Int) = shown(text(id))

    private fun shown(text: String) =
        compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    // Its chip, or its panel when open (#649).
    private val developerShown
        get() =
            listOf(SettingsSection.DEVELOPER.tag, SettingsSection.DEVELOPER.panelTag).any {
                compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty()
            }

    private fun show(
        onOpenDiagnostics: () -> Unit = {},
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
                        onOpenDiagnostics = onOpenDiagnostics,
                    ) { "Label of $it" }
                }
            }
            compose.waitUntil(WAIT_MS) { shown(R.string.settings_title) }
            scenario.block()
        }
    }

    private fun tapVersion() {
        compose.onNodeWithTag(VERSION_TAG).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun tapsLeft(left: Int) =
        app.resources.getQuantityString(R.plurals.settings_developer_taps_left, left, left)

    @Test
    fun sevenTapsOnVersionTurnDeveloperModeOnAndItsSwitchTurnsItOff() =
        show {
            assertFalse(developerShown)
            compose.openSection(SettingsSection.ABOUT)
            // The version and the build, as `versionName` and `versionCode` have them.
            assertTrue(shown("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))

            // The first three taps say nothing; from the fourth, the taps left are counted down.
            repeat(3) { tapVersion() }
            assertNull(ShadowToast.getTextOfLatestToast())
            for (left in 3 downTo 1) {
                tapVersion()
                assertEquals(tapsLeft(left), ShadowToast.getTextOfLatestToast())
            }
            assertFalse(settings.now().developerMode)
            tapVersion()
            // Literal text, not the resource, to pin the wording #669 settled on.
            assertEquals("Developer mode is enabled", ShadowToast.getTextOfLatestToast())
            compose.waitUntil(WAIT_MS) { settings.now().developerMode && developerShown }

            // Once on, a tap says so.
            tapVersion()
            assertEquals("Developer mode is already enabled", ShadowToast.getTextOfLatestToast())

            // The section's switch turns developer mode off, and its options with it.
            compose.openSection(SettingsSection.DEVELOPER)
            compose.onNodeWithText(text(R.string.settings_diagnostics)).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { settings.now().showDiagnostics }
            val mode = compose.onNodeWithText(text(R.string.settings_developer_mode)).performScrollTo()
            mode.assertIsOn()
            mode.performClick()
            compose.waitUntil(WAIT_MS) { !settings.now().developerMode && !developerShown }
            assertFalse("Diagnostics off with developer mode", settings.now().showDiagnostics)

            // *About* still unfolded: the count starts afresh, and seven taps turn it on again.
            repeat(4) { tapVersion() }
            assertEquals(tapsLeft(3), ShadowToast.getTextOfLatestToast())
            repeat(3) { tapVersion() }
            assertEquals(text(R.string.settings_developer_on), ShadowToast.getTextOfLatestToast())
            compose.waitUntil(WAIT_MS) { settings.now().developerMode && developerShown }
        }

    @Test
    fun theWayToTheDiagnosticsShowsOnlyWhileTheSettingIsOn() {
        runBlocking { settings.update { it.copy(developerMode = true) } }
        var opened = 0
        show(onOpenDiagnostics = { opened++ }) {
            compose.openSection(SettingsSection.DEVELOPER)
            compose.waitUntil(WAIT_MS) { shown(R.string.settings_diagnostics) }
            assertEquals(false, shown(R.string.settings_diagnostics_open))

            compose.onNodeWithText(text(R.string.settings_diagnostics)).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { shown(R.string.settings_diagnostics_open) }
            assertEquals(true, settings.now().showDiagnostics)

            compose.onNodeWithText(text(R.string.settings_diagnostics_open)).performScrollTo().performClick()
            assertEquals(1, opened)

            compose.onNodeWithText(text(R.string.settings_diagnostics)).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { !shown(R.string.settings_diagnostics_open) }
        }
    }

    @Test
    fun showGroundingDetailsIsADeveloperOptionOffByDefaultAndOffWithDeveloperMode() {
        runBlocking { settings.update { it.copy(developerMode = true) } }
        show {
            compose.openSection(SettingsSection.DEVELOPER)
            compose.waitUntil(WAIT_MS) { shown(R.string.settings_grounding_details) }
            val details = compose.onNodeWithText(text(R.string.settings_grounding_details)).performScrollTo()
            details.assertIsOff()
            details.performClick()
            compose.waitUntil(WAIT_MS) { settings.now().showGroundingDetails }

            compose.onNodeWithText(text(R.string.settings_developer_mode)).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { !settings.now().developerMode && !developerShown }
            assertFalse("Grounding details off with developer mode", settings.now().showGroundingDetails)
        }
    }

    @Test
    fun aPlayersPackageNameShowsOnlyInDeveloperMode() =
        show {
            compose.openSection(SettingsSection.PLAYERS)
            compose.waitUntil(WAIT_MS) { shown("Label of ${Settings.SPOTIFY}") }
            assertFalse(shown(Settings.SPOTIFY))
            runBlocking { settings.update { it.copy(developerMode = true) } }
            compose.waitUntil(WAIT_MS) { shown(Settings.SPOTIFY) }
        }
}
