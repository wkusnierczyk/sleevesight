package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.app.ui.theme.GroupArrangement
import app.sleevesight.app.ui.theme.groupArrangement
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *AI web search* (#801): *Once* by default, stored under its own key; under *Insights* before *Ask
 * AI automatically*, a group of three kept to one row, *Up to 3 times* with its limit fixed, no
 * setting for it; with Exa web search off, *Off* chosen, the others disabled, the choice kept, and
 * the two lines with the link to *Sources*.
 */
@RunWith(RobolectricTestRunner::class)
class AiWebSearchSettingTest {
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

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun onceIsTheDefaultAndTheChoiceIsStoredUnderItsOwnKey() {
        assertEquals(AiWebSearch.ONCE, Settings().aiWebSearch)
        val prefs = mutablePreferencesOf()
        val chosen = Settings(aiWebSearch = AiWebSearch.UP_TO)
        SettingsCodec.write(chosen, prefs)
        assertEquals(mutablePreferencesOf(stringPreferencesKey("insights.ai_web_search") to "UP_TO"), prefs)
        assertEquals(chosen, SettingsCodec.read(prefs))
        // The limit is fixed, not a setting: none is stored.
        assertEquals(3, Settings.AI_WEB_SEARCH_LIMIT)
    }

    @Test
    fun theTextsAreThePickedOnes() {
        assertEquals("AI web search", text(R.string.settings_ai_web_search))
        assertEquals(
            "Lets your AI service search the web with Exa when it is asked, by a tap on Ask AI or automatically.",
            text(R.string.settings_ai_web_search_summary),
        )
        assertEquals("Up to 3 times", app.resources.getQuantityString(R.plurals.settings_ai_web_search_up_to, 3, 3))
        assertEquals(
            "Sleevesight searches the web once with Exa and gives the pages it finds to your AI service, which " +
                "answers from them. One Exa API call and one paid AI call.",
            text(R.string.settings_ai_web_search_once_detail),
        )
        assertEquals(
            "Your AI service searches the web with Exa itself, up to 3 times, changing the search as it learns, then " +
                "answers from the pages. Up to 3 Exa API calls and 4 paid AI calls.",
            app.resources.getQuantityString(R.plurals.settings_ai_web_search_up_to_detail, 3, 3, 4),
        )
        assertEquals("Exa web search is disabled.", text(R.string.settings_ai_web_search_exa_off))
    }

    @Test
    fun aGroupKeptToOneRowNeverStacks() {
        assertEquals(GroupArrangement.STACKED, groupArrangement(listOf(300, 300, 300), 2, 500))
        assertEquals(GroupArrangement.PROPORTIONAL, groupArrangement(listOf(300, 300, 300), 2, 500, oneRow = true))
    }

    @Test
    fun theGroupComesBeforeTheToggleWithUpToThreeTimesAndNoLimitToSet() =
        showSettings {
            compose.openSection(SettingsSection.INSIGHTS)
            waitForText(text(R.string.settings_ai_web_search))
            val group =
                compose
                    .onNodeWithText(
                        text(R.string.settings_ai_web_search),
                    ).performScrollTo()
                    .fetchSemanticsNode()
            val toggle = compose.onNodeWithText(text(R.string.settings_search_with_ai)).fetchSemanticsNode()
            assertTrue(group.positionInRoot.y < toggle.positionInRoot.y)
            compose.onNodeWithText(text(R.string.settings_ai_web_search_once)).performScrollTo().assertIsSelected()
            compose.onNodeWithText("Up to 3 times").performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { settings.now().aiWebSearch == AiWebSearch.UP_TO }
            assertTrue(compose.onAllNodesWithText("Web searches at most").fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun withExaOffOffShowsChosenTheOthersCannotBeAndTheChoiceIsKept() =
        showSettings {
            runBlocking {
                settings.update {
                    it.copy(aiWebSearch = AiWebSearch.UP_TO, enabledSources = it.enabledSources - SourceSwitch.WEB)
                }
            }
            compose.openSection(SettingsSection.INSIGHTS)
            waitForText(text(R.string.settings_ai_web_search_exa_off))
            compose
                .onNodeWithText(
                    text(R.string.settings_ai_web_search_off),
                ).performScrollTo()
                .assertIsSelected()
                .assertIsEnabled()
            compose.onNodeWithText(text(R.string.settings_ai_web_search_once)).assertIsNotEnabled()
            compose.onNodeWithText("Up to 3 times").assertIsNotEnabled()
            assertTrue(shows("See Settings → Sources."))
            // A tap on Off keeps the choice saved.
            compose.onNodeWithText(text(R.string.settings_ai_web_search_off)).performClick()
            compose.waitForIdle()
            assertEquals(AiWebSearch.UP_TO, settings.now().aiWebSearch)
            assertEquals(AiWebSearch.OFF, settings.now().aiWebSearchUsed)
        }
}
