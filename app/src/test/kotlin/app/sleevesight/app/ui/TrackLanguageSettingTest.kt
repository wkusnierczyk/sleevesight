package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.settings.SettingsStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.TrackLanguageSearch
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
 * *Search in the track’s language* (#902): under *Sources*, after *Exa web search*, a group *Off* |
 * *If needed* | *Always*, *If needed* chosen by default, what the chosen value does under it, and a
 * tap stored at once.
 */
@RunWith(RobolectricTestRunner::class)
class TrackLanguageSettingTest {
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

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun showSources(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.SOURCES)
            compose.waitUntil(WAIT_MS) { shows(text(R.string.settings_track_language)) }
            block()
        }
    }

    @Test
    fun theTextsAreThePickedOnes() {
        assertEquals("Search in the track’s language", text(R.string.settings_track_language))
        assertEquals(
            "When a track is sung in a language other than English, also looks it up on Wikipedia and searches " +
                "the web in that language. The insight translates what they give and says which citations it " +
                "translated.",
            text(R.string.settings_track_language_summary),
        )
        assertEquals("Off", text(R.string.settings_track_language_off))
        assertEquals("If needed", text(R.string.settings_track_language_if_needed))
        assertEquals("Always", text(R.string.settings_track_language_always))
        assertEquals("Looks up every track in English only.", text(R.string.settings_track_language_off_detail))
        assertEquals(
            "Searches in the track’s language only when the English sources say little about the track itself. " +
                "With Exa on, one more Exa API call for such a track.",
            text(R.string.settings_track_language_if_needed_detail),
        )
        assertEquals(
            "Searches in the track’s language for every track that has one other than English. With Exa on, one " +
                "more Exa API call for each such track.",
            text(R.string.settings_track_language_always_detail),
        )
    }

    @Test
    fun theRowComesAfterExaWebSearchWithIfNeededChosenByDefault() =
        showSources {
            val row =
                compose
                    .onNodeWithText(text(R.string.settings_track_language))
                    .performScrollTo()
                    .fetchSemanticsNode()
            val exa = compose.onNodeWithText(text(R.string.settings_engine_exa)).fetchSemanticsNode()
            val model = compose.onNodeWithText(text(R.string.settings_source_model)).fetchSemanticsNode()
            assertTrue(exa.positionInRoot.y < row.positionInRoot.y)
            assertTrue(row.positionInRoot.y < model.positionInRoot.y)
            compose
                .onNodeWithText(text(R.string.settings_track_language_if_needed))
                .performScrollTo()
                .assertIsSelected()
            assertTrue(shows(text(R.string.settings_track_language_if_needed_detail)))
            assertTrue(!shows(text(R.string.settings_track_language_always_detail)))
        }

    @Test
    fun aTapIsStoredAndTheLineUnderTheGroupFollows() =
        showSources {
            compose
                .onNodeWithText(text(R.string.settings_track_language_always))
                .performScrollTo()
                .performClick()
            compose.waitUntil(WAIT_MS) { settings.now().trackLanguageSearch == TrackLanguageSearch.ALWAYS }
            compose.waitUntil(WAIT_MS) { shows(text(R.string.settings_track_language_always_detail)) }
            compose
                .onNodeWithText(text(R.string.settings_track_language_off))
                .performScrollTo()
                .performClick()
            compose.waitUntil(WAIT_MS) { settings.now().trackLanguageSearch == TrackLanguageSearch.OFF }
            compose.waitUntil(WAIT_MS) { shows(text(R.string.settings_track_language_off_detail)) }
        }
}
