package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsCodec
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SEARCH_FIELD_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *One panel for Dates, Genres and Lyrics* (#1128): on by default, also for a settings file from
 * before it, stored under its own key when off; a switch row under *Appearance* with the picked
 * texts, which moves the setting; the settings' search finds it. What it does on Home is
 * `OnePanelScreenTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
class OnePanelSettingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private fun showSettings(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            block()
        }
    }

    @Test
    fun itIsOnByDefaultAlsoForAFileFromBeforeIt() {
        assertTrue(Settings().onePanel)
        // As stored before the setting existed: another setting, and not its key.
        assertTrue(SettingsCodec.read(mutablePreferencesOf(stringPreferencesKey("display.theme") to "DARK")).onePanel)
    }

    @Test
    fun offItIsStoredUnderItsOwnKeyAndReadBack() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(onePanel = false), prefs)
        assertEquals(mutablePreferencesOf(booleanPreferencesKey("display.one_panel") to false), prefs)
        assertEquals(Settings(onePanel = false), SettingsCodec.read(prefs))
        SettingsCodec.write(Settings(), prefs)
        assertTrue("back on, it is the default and has no key", prefs.asMap().isEmpty())
    }

    @Test
    fun theRowHasThePickedTexts() {
        assertEquals("One panel for Dates, Genres and Lyrics", text(R.string.settings_one_panel))
        assertEquals(
            "Dates, Genres and Lyrics on the home screen open one shared panel, with the three buttons at its " +
                "top to switch between them.",
            text(R.string.settings_one_panel_summary),
        )
    }

    @Test
    fun theSwitchUnderAppearanceMovesTheSetting() =
        showSettings {
            compose.openSection(SettingsSection.APPEARANCE)
            val name = text(R.string.settings_one_panel)
            compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(text(R.string.settings_one_panel_summary)).performScrollTo().assertExists()
            val row = compose.onNodeWithText(name).performScrollTo()
            row.assertIsOn()
            row.performClick()
            compose.waitUntil(WAIT_MS) { !settings.now().onePanel }
            row.assertIsOff()
            row.performClick()
            compose.waitUntil(WAIT_MS) { settings.now().onePanel }
            row.assertIsOn()
        }

    @Test
    fun theSettingsSearchFindsTheRow() =
        showSettings {
            compose.waitUntil(WAIT_MS) {
                compose
                    .onAllNodesWithText(text(R.string.settings_section_appearance))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            for (query in listOf("one panel", "switch between them")) {
                compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(query)
                compose.waitForIdle()
                compose
                    .onNodeWithText(text(R.string.settings_one_panel), substring = true)
                    .performScrollTo()
                    .assertExists()
            }
        }
}
