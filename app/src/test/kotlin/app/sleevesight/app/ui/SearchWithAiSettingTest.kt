package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsCodec
import app.sleevesight.app.settings.SettingsStore
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Search with AI when a source has nothing* (#773): off by default, also for a settings file from
 * before it, stored under its own key when on; a switch row under *Insights* with the picked texts,
 * not to be changed while *Show insights* is off; the settings' search finds it.
 */
@RunWith(RobolectricTestRunner::class)
class SearchWithAiSettingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val settings = SettingsStore(MemoryStore())
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    private fun waitForText(text: String) =
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun itIsOffByDefaultAlsoForAFileFromBeforeIt() {
        assertFalse(Settings().searchWithAi)
        val before = SettingsCodec.read(mutablePreferencesOf(stringPreferencesKey("display.theme") to "DARK"))
        assertFalse(before.searchWithAi)
    }

    @Test
    fun onItIsStoredUnderItsOwnKeyAndReadBack() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(searchWithAi = true), prefs)
        assertEquals(mutablePreferencesOf(booleanPreferencesKey("insights.search_with_ai") to true), prefs)
        assertEquals(Settings(searchWithAi = true), SettingsCodec.read(prefs))
        SettingsCodec.write(Settings(), prefs)
        assertTrue("back off, it is the default and has no key", prefs.asMap().isEmpty())
    }

    @Test
    fun theRowHasThePickedTexts() {
        assertEquals("Search with AI when a source has nothing", text(R.string.settings_search_with_ai))
        assertEquals(
            "Asks your AI service at once when Wikipedia has no definition for a genre, or Dates, Genres or " +
                "Lyrics find nothing. Each search is a paid call.",
            text(R.string.settings_search_with_ai_summary),
        )
    }

    @Test
    fun theSwitchUnderInsightsTurnsItOnAndOffAndRestsWhileShowInsightsIsOff() =
        showSettings {
            compose.openSection(SettingsSection.INSIGHTS)
            val title = text(R.string.settings_search_with_ai)
            waitForText(title)
            compose.onNodeWithText(text(R.string.settings_search_with_ai_summary)).performScrollTo().assertExists()
            val row = compose.onNodeWithText(title).performScrollTo()
            row.assertIsOff().assertIsEnabled()
            row.performClick()
            compose.waitUntil(WAIT_MS) { settings.now().searchWithAi }
            row.assertIsOn()
            row.performClick()
            compose.waitUntil(WAIT_MS) { !settings.now().searchWithAi }
            // *Show insights* off: no AI call is made, and the switch is not to be moved.
            compose.onNodeWithText(text(R.string.settings_write_insights)).performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { !settings.now().writeInsights }
            compose
                .onNodeWithText(title)
                .performScrollTo()
                .assertIsNotEnabled()
        }

    @Test
    fun theSettingsSearchFindsIt() =
        showSettings {
            compose.waitUntil(WAIT_MS) {
                compose.onAllNodesWithText(text(R.string.settings_section_insights)).fetchSemanticsNodes().isNotEmpty()
            }
            for (query in listOf("search with AI", "a source has nothing", "Each search is a paid call")) {
                compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(query)
                compose.waitForIdle()
                compose
                    .onNodeWithText(
                        text(R.string.settings_search_with_ai),
                        substring = true,
                    ).performScrollTo()
                    .assertExists()
            }
        }
}
