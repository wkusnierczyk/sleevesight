package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.TestStores
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsCodec
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.ThemeChoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Theme button* (#636): on by default, also for a settings file from before it, stored under its
 * own key when off, a switch under *Appearance*; the theme button's save moves the stored *Theme*
 * one step per tap from what is stored, and *Theme*'s row shows what it chose.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeButtonSettingTest {
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

    // The settings, *Appearance* open, the scenario kept open for [block].
    private fun showSettings(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.APPEARANCE)
            block()
        }
    }

    @Test
    fun theButtonIsOnByDefaultAlsoForAFileFromBeforeIt() {
        assertTrue(Settings().themeButton)
        // As stored before the setting existed: other settings, and no theme button.
        assertTrue(
            SettingsCodec.read(mutablePreferencesOf(stringPreferencesKey("display.theme") to "DARK")).themeButton,
        )
    }

    @Test
    fun theButtonSwitchedOffIsStoredUnderItsKeyAndReadBack() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(themeButton = false), prefs)
        assertEquals(mutablePreferencesOf(booleanPreferencesKey("display.theme_button") to false), prefs)
        assertFalse(SettingsCodec.read(prefs).themeButton)
    }

    @Test
    fun eachSaveMovesTheStoredThemeOneStepFromWhatIsStored() {
        val cycle = settings.themeCycler(saves)
        for (expected in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK, ThemeChoice.SYSTEM)) {
            cycle()
            compose.waitUntil(5_000) { settings.now().theme == expected }
        }
        // Two taps before either is saved still move it two steps: each reads what is stored.
        cycle()
        cycle()
        compose.waitUntil(5_000) { settings.now().theme == ThemeChoice.DARK }
    }

    @Test
    fun theSwitchUnderAppearanceTurnsTheButtonOffAndOn() =
        showSettings {
            val title = text(R.string.settings_theme_button)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            val row = compose.onNodeWithText(title).performScrollTo()
            row.assertIsOn()
            row.performClick()
            compose.waitUntil(5_000) { !settings.now().themeButton }
            row.assertIsOff()
            row.performClick()
            compose.waitUntil(5_000) { settings.now().themeButton }
        }

    @Test
    fun theThemeRowShowsWhatTheButtonChose() =
        showSettings {
            val cycle = settings.themeCycler(saves)
            for ((theme, label) in listOf(
                ThemeChoice.LIGHT to R.string.settings_theme_light,
                ThemeChoice.DARK to R.string.settings_theme_dark,
                ThemeChoice.SYSTEM to R.string.settings_theme_system,
            )) {
                cycle()
                compose.waitUntil(5_000) { settings.now().theme == theme }
                compose.waitForIdle()
                compose.onNode(hasText(text(label)) and isSelectable()).performScrollTo().assertIsSelected()
            }
        }
}
