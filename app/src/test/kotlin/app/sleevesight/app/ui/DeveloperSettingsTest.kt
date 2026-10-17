package app.sleevesight.app.ui

import androidx.activity.compose.setContent
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
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** *Diagnostics*, the developer setting, and the way to the diagnostics screen behind it (#54). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeveloperSettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val stores by lazy { TestStores(tmp.root) }
    private val settings by lazy { SettingsStore(stores.open("settings")) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }

    @After
    fun close() = stores.close()

    private fun text(id: Int) = app.getString(id)

    private fun shown(id: Int) = compose.onAllNodesWithText(text(id)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theWayToTheDiagnosticsShowsOnlyWhileTheSettingIsOn() {
        var opened = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(
                        settings,
                        secrets,
                        emptyMap(),
                        { CompletableDeferred(true) },
                        onOpenDiagnostics = { opened++ },
                    ) { it }
                }
            }
            compose.waitUntil(5_000) { shown(R.string.settings_diagnostics) }
            assertEquals(false, shown(R.string.settings_diagnostics_open))

            compose.onNodeWithText(text(R.string.settings_diagnostics)).performScrollTo().performClick()
            compose.waitUntil(5_000) { shown(R.string.settings_diagnostics_open) }
            assertEquals(true, runBlocking { settings.settings.first() }.showDiagnostics)

            compose.onNodeWithText(text(R.string.settings_diagnostics_open)).performScrollTo().performClick()
            assertEquals(1, opened)

            compose.onNodeWithText(text(R.string.settings_diagnostics)).performScrollTo().performClick()
            compose.waitUntil(5_000) { !shown(R.string.settings_diagnostics_open) }
        }
    }
}
