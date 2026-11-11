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
import app.sleevesight.app.memorySettings
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
import app.sleevesight.core.settings.LookUpAfterClear
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
 * *Look up the track again* (#1112): under *History*, just below *Clear history*, a group
 * *Automatically* | *On request*, *Automatically* chosen by default, a tap stored at once; and the
 * message after a clear, which says the track on Home is looked up again only under *Automatically*,
 * the same choice the clear is told.
 */
@RunWith(RobolectricTestRunner::class)
class LookUpAgainSettingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()
    private val settings = memorySettings()
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // What each clear was told: whether the track on Home is looked up again.
    private val clears = mutableListOf<Boolean>()

    @After
    fun close() {
        saves.cancel()
    }

    private fun text(id: Int) = app.getString(id)

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun showHistory(block: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), {
                        clears += it
                        CompletableDeferred(true)
                    }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.HISTORY)
            compose.waitUntil(WAIT_MS) { shows(text(R.string.settings_look_up_again)) }
            block()
        }
    }

    private fun top(id: Int): Float =
        compose
            .onNodeWithText(text(id))
            .fetchSemanticsNode()
            .positionInRoot.y

    private fun clearHistory() {
        compose.onNodeWithText(text(R.string.settings_clear_history)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.settings_clear)).performClick()
    }

    @Test
    fun theTextsAreThePickedOnes() {
        assertEquals("Look up the track again", text(R.string.settings_look_up_again))
        assertEquals(
            "After Clear history, the evidence for the current track on Home is wiped. It can be looked up " +
                "again automatically, or only when you tap the Look up again button. Looking it up asks the " +
                "sources again and, if insights are written automatically, writes the insight again as well.",
            text(R.string.settings_look_up_again_summary),
        )
        assertEquals("Automatically", text(R.string.settings_look_up_again_automatically))
        assertEquals("On request", text(R.string.settings_look_up_again_on_request))
        assertEquals(
            "History cleared. The track on Home is looked up again.",
            text(R.string.settings_clear_history_done_looked_up),
        )
        assertEquals("History cleared.", text(R.string.settings_clear_history_done))
        assertEquals("This track’s findings were cleared with the history.", text(R.string.home_insight_cleared))
        assertEquals("Cleared with the history.", text(R.string.home_cleared))
        assertEquals("Look up again", text(R.string.home_look_up_again))
    }

    @Test
    fun theRowSitsJustBelowClearHistoryWithAutomaticallyChosenByDefault() =
        showHistory {
            val clear = top(R.string.settings_clear_history)
            val row = top(R.string.settings_look_up_again)
            val gap = top(R.string.settings_session_gap)
            assertTrue("below Clear history", clear < row)
            assertTrue("above the next setting", row < gap)
            compose
                .onNodeWithText(text(R.string.settings_look_up_again_automatically))
                .performScrollTo()
                .assertIsSelected()
        }

    @Test
    fun aTapIsStored() =
        showHistory {
            compose
                .onNodeWithText(text(R.string.settings_look_up_again_on_request))
                .performScrollTo()
                .performClick()
            compose.waitUntil(WAIT_MS) { settings.now().lookUpAfterClear == LookUpAfterClear.ON_REQUEST }
            compose
                .onNodeWithText(text(R.string.settings_look_up_again_automatically))
                .performScrollTo()
                .performClick()
            compose.waitUntil(WAIT_MS) { settings.now().lookUpAfterClear == LookUpAfterClear.AUTOMATICALLY }
        }

    @Test
    fun automaticallyTheMessageSaysTheTrackIsLookedUpAgain() =
        showHistory {
            clearHistory()
            compose.waitUntil(WAIT_MS) { shows(text(R.string.settings_clear_history_done_looked_up)) }
            assertEquals(listOf(true), clears)
        }

    @Test
    fun onRequestTheMessageStaysHistoryCleared() =
        showHistory {
            compose
                .onNodeWithText(text(R.string.settings_look_up_again_on_request))
                .performScrollTo()
                .performClick()
            compose.waitUntil(WAIT_MS) { settings.now().lookUpAfterClear == LookUpAfterClear.ON_REQUEST }
            clearHistory()
            compose.waitUntil(WAIT_MS) { shows(text(R.string.settings_clear_history_done)) }
            assertFalse(shows(text(R.string.settings_clear_history_done_looked_up)))
            assertEquals(listOf(false), clears)
        }
}
