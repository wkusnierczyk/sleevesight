package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
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
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.ReportButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Report button* (#452, #518) under *Developer*: a row with its choice, *Show*, *Auto* or *Hide*,
 * picked in a dialog as the other choices are; and *Developer mode* off setting *Hide*.
 */
@RunWith(RobolectricTestRunner::class)
class ReportButtonSettingsScreenTest {
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

    private fun shown(id: Int) = compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty()

    private fun choose(option: Int) {
        compose.onNodeWithText(text(R.string.settings_report_button)).performScrollTo().performClick()
        compose.onNode(hasText(text(option)) and hasAnyAncestor(isDialog())).performClick()
    }

    // The row's value: the choice's label, outside the dialog.
    private fun rowShows(option: Int) =
        compose.onAllNodes(hasText(text(option)) and !hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theRowPicksEachChoiceAndDeveloperModeOffSetsHide() {
        runBlocking { settings.update { it.copy(developerMode = true) } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.DEVELOPER)
            compose.waitUntil(5_000) { shown(R.string.settings_report_button) }
            compose.onNodeWithText(text(R.string.settings_report_button_summary)).assertExists()
            // `:core`'s default, as in any build but the test build.
            assertEquals(true, rowShows(R.string.settings_report_button_hide))

            for (
            (option, choice) in
            listOf(
                R.string.settings_report_button_auto to ReportButton.AUTO,
                R.string.settings_report_button_show to ReportButton.SHOW,
                R.string.settings_report_button_hide to ReportButton.HIDE,
                R.string.settings_report_button_show to ReportButton.SHOW,
            )
            ) {
                choose(option)
                compose.waitUntil(5_000) { settings.now().reportButton == choice && rowShows(option) }
            }

            compose.onNodeWithText(text(R.string.settings_developer_mode)).performScrollTo().performClick()
            compose.waitUntil(5_000) { !settings.now().developerMode }
            assertEquals("Hidden with developer mode off", ReportButton.HIDE, settings.now().reportButton)
        }
    }
}
