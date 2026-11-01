package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.intPreferencesKey
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
import app.sleevesight.app.ui.settings.LIMIT_VALUE_TAG
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
 * *AI web search* (#801): *Once* by default, stored under its own key with its limit; under *Insights*
 * after *Ask AI automatically*, a group of three kept to one row, *Web searches at most* only with
 * *Up to {n} times*, stepping 2 to 5; with Exa web search off, *Off* chosen, the others disabled, the
 * choice kept, and the two lines with the link to *Sources*.
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
    fun onceIsTheDefaultAndTheChoiceAndLimitAreStoredUnderTheirOwnKeys() {
        assertEquals(AiWebSearch.ONCE, Settings().aiWebSearch)
        assertEquals(3, Settings().aiWebSearchLimit)
        val prefs = mutablePreferencesOf()
        val chosen = Settings(aiWebSearch = AiWebSearch.UP_TO, aiWebSearchLimit = 5)
        SettingsCodec.write(chosen, prefs)
        assertEquals(
            mutablePreferencesOf(
                stringPreferencesKey("insights.ai_web_search") to "UP_TO",
                intPreferencesKey("insights.ai_web_search_limit") to 5,
            ),
            prefs,
        )
        assertEquals(chosen, SettingsCodec.read(prefs))
        // A limit off the choices is the default.
        assertEquals(
            3,
            SettingsCodec
                .read(
                    mutablePreferencesOf(intPreferencesKey("insights.ai_web_search_limit") to 9),
                ).aiWebSearchLimit,
        )
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
            "Your AI service searches the web with Exa itself, up to 3 times, changing the search as it learns, then " +
                "answers from the pages. Up to 3 paid Exa searches and 4 paid AI calls.",
            app.resources.getQuantityString(R.plurals.settings_ai_web_search_up_to_detail, 3, 3, 4),
        )
        assertEquals("Web searches at most", text(R.string.settings_ai_web_search_limit))
        assertEquals("Exa web search is disabled.", text(R.string.settings_ai_web_search_exa_off))
    }

    @Test
    fun aGroupKeptToOneRowNeverStacks() {
        assertEquals(GroupArrangement.STACKED, groupArrangement(listOf(300, 300, 300), 2, 500))
        assertEquals(GroupArrangement.PROPORTIONAL, groupArrangement(listOf(300, 300, 300), 2, 500, oneRow = true))
    }

    @Test
    fun upToNShowsTheLimitWhichStepsFromTwoToFive() =
        showSettings {
            compose.openSection(SettingsSection.INSIGHTS)
            waitForText(text(R.string.settings_ai_web_search))
            compose.onNodeWithText(text(R.string.settings_ai_web_search_once)).performScrollTo().assertIsSelected()
            assertTrue(compose.onAllNodesWithTag(LIMIT_VALUE_TAG).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithText("Up to 3 times").performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { settings.now().aiWebSearch == AiWebSearch.UP_TO }
            val increase = compose.onNodeWithContentDescription(text(R.string.settings_stepper_increase))
            increase.performScrollTo().performClick()
            compose.waitUntil(WAIT_MS) { settings.now().aiWebSearchLimit == 4 }
            increase.performClick()
            compose.waitUntil(WAIT_MS) { settings.now().aiWebSearchLimit == 5 }
            increase.assertIsNotEnabled()
            waitForText("Up to 5 times")
            compose.onNodeWithTag(LIMIT_VALUE_TAG).assertExists()
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
            // No limit while it cannot search; a tap on Off keeps the choice saved.
            assertTrue(compose.onAllNodesWithTag(LIMIT_VALUE_TAG).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithText(text(R.string.settings_ai_web_search_off)).performClick()
            compose.waitForIdle()
            assertEquals(AiWebSearch.UP_TO, settings.now().aiWebSearch)
            assertEquals(AiWebSearch.OFF, settings.now().aiWebSearchUsed)
        }
}
