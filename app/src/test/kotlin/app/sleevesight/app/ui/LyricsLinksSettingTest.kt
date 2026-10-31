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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *Lyrics links* and *Search Genius when there is no link* (#728): both on by default, also for a
 * settings file from before them, each stored under its own key when off; two switch rows under
 * *Appearance* with the picked texts, the second usable only while the first is on; the settings'
 * search finds both.
 */
@RunWith(RobolectricTestRunner::class)
class LyricsLinksSettingTest {
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
    fun bothAreOnByDefaultAlsoForAFileFromBeforeThem() {
        assertTrue(Settings().lyricsLinks)
        assertTrue(Settings().lyricsSearch)
        // As stored before the settings existed: other settings, and neither key.
        val before = SettingsCodec.read(mutablePreferencesOf(stringPreferencesKey("display.theme") to "DARK"))
        assertTrue(before.lyricsLinks)
        assertTrue(before.lyricsSearch)
    }

    @Test
    fun eachSwitchedOffIsStoredUnderItsOwnKeyAndReadBack() {
        val prefs = mutablePreferencesOf()
        SettingsCodec.write(Settings(lyricsLinks = false), prefs)
        assertEquals(mutablePreferencesOf(booleanPreferencesKey("display.lyrics_links") to false), prefs)
        SettingsCodec.write(Settings(lyricsLinks = false, lyricsSearch = false), prefs)
        assertEquals(
            mutablePreferencesOf(
                booleanPreferencesKey("display.lyrics_links") to false,
                booleanPreferencesKey("display.lyrics_search") to false,
            ),
            prefs,
        )
        // Read back whole: `SettingsCodecTest.everySettingRoundTrips` is at its size limit.
        assertEquals(Settings(lyricsLinks = false, lyricsSearch = false), SettingsCodec.read(prefs))
        SettingsCodec.write(Settings(), prefs)
        assertTrue("back on, they are the defaults and have no key", prefs.asMap().isEmpty())
    }

    @Test
    fun theRowsHaveThePickedTexts() {
        assertEquals("Lyrics links", text(R.string.settings_lyrics_links))
        assertEquals(
            "Home shows a Lyrics button for the playing track, with links to its lyrics on the lyrics sites " +
                "MusicBrainz links to, or, when it links to none, a search on Genius. A link opens in your " +
                "browser; Sleevesight doesn’t show lyrics.",
            text(R.string.settings_lyrics_links_summary),
        )
        assertEquals("Search Genius when there is no link", text(R.string.settings_lyrics_search))
    }

    @Test
    fun theSearchSwitchIsUsableOnlyWhileTheLinksAreOn() =
        showSettings {
            compose.openSection(SettingsSection.APPEARANCE)
            val links = text(R.string.settings_lyrics_links)
            val search = text(R.string.settings_lyrics_search)
            waitForText(links)
            compose.onNodeWithText(text(R.string.settings_lyrics_links_summary)).performScrollTo().assertExists()
            val searchRow = compose.onNodeWithText(search).performScrollTo()
            searchRow.assertIsOn().assertIsEnabled()
            searchRow.performClick()
            compose.waitUntil(WAIT_MS) { !settings.now().lyricsSearch }
            searchRow.assertIsOff()
            searchRow.performClick()
            compose.waitUntil(WAIT_MS) { settings.now().lyricsSearch }

            val linksRow = compose.onNodeWithText(links).performScrollTo()
            linksRow.assertIsOn()
            linksRow.performClick()
            compose.waitUntil(WAIT_MS) { !settings.now().lyricsLinks }
            linksRow.assertIsOff()
            // Kept as it was, but not to be moved.
            compose
                .onNodeWithText(search)
                .performScrollTo()
                .assertIsOn()
                .assertIsNotEnabled()
                .performClick()
            compose.waitForIdle()
            assertTrue(settings.now().lyricsSearch)
            linksRow.performClick()
            compose.waitUntil(WAIT_MS) { settings.now().lyricsLinks }
            compose.onNodeWithText(search).performScrollTo().assertIsEnabled()
        }

    @Test
    fun theSettingsSearchFindsBothRows() =
        showSettings {
            compose.waitUntil(WAIT_MS) {
                compose
                    .onAllNodesWithText(
                        text(R.string.settings_section_appearance),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            for (query in listOf("lyrics links", "search genius", "Sleevesight doesn’t show lyrics")) {
                compose.onNodeWithTag(SEARCH_FIELD_TAG).performTextReplacement(query)
                compose.waitForIdle()
                val title =
                    if (query ==
                        "search genius"
                    ) {
                        R.string.settings_lyrics_search
                    } else {
                        R.string.settings_lyrics_links
                    }
                compose.onNodeWithText(text(title), substring = true).performScrollTo().assertExists()
            }
        }
}
