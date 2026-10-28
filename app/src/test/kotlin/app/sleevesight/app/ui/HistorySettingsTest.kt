package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
import app.sleevesight.app.ui.settings.SettingsSection
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
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * *History*'s layout and text (#547): *Clear history* sits right under *Keep history for*, which
 * offers *Forever*, and says what a tap does (#669); no description in the section starts from the
 * off state.
 */
@RunWith(RobolectricTestRunner::class)
class HistorySettingsTest {
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

    /** Shows the settings screen with *History* unfolded, and every other section folded. */
    private fun showHistory(check: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
                }
            }
            compose.openSection(SettingsSection.HISTORY)
            val title = text(R.string.settings_clear_history)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            check()
        }
    }

    private fun top(id: Int): Float =
        compose
            .onNodeWithText(text(id))
            .fetchSemanticsNode()
            .positionInRoot.y

    @Test
    fun clearHistorySitsRightUnderKeepHistoryFor() =
        showHistory {
            val rows =
                listOf(R.string.settings_keep_history, R.string.settings_clear_history, R.string.settings_session_gap)
            val tops = rows.map(::top)
            val order = "Keep history for, Clear history, New session idle trigger, in that order"
            assertEquals(order, tops.sorted(), tops)
        }

    @Test
    fun keepHistoryForSaysTheInsightsAndGenreExplanationsFollowIt() =
        showHistory {
            // #643: the insights written are kept and shown no longer than the history, so it says so.
            val summary = text(R.string.settings_keep_history_summary)
            assertTrue(summary, "insights and genre explanations" in summary)
            val rows =
                listOf(
                    R.string.settings_keep_history,
                    R.string.settings_keep_history_summary,
                    R.string.settings_clear_history,
                )
            val tops = rows.map(::top)
            assertEquals("the title, its description, then Clear history", tops.sorted(), tops)
        }

    @Test
    fun clearHistorySaysWhatATapDoesAsAnAction() =
        showHistory {
            // An action, as a button's label is, not a description of the row (#669).
            val line = "Delete everything Sleevesight kept about the tracks you played"
            assertTrue(compose.onAllNodesWithText(line).fetchSemanticsNodes().isNotEmpty())
        }

    @Test
    fun noDescriptionInHistoryStartsFromTheOffState() =
        showHistory {
            // Only *History* is unfolded, so every text with an "Off:" at its start would be one of its own.
            val texts = mutableListOf<String>()

            fun collect(node: SemanticsNode) {
                node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
                node.children.forEach(::collect)
            }
            collect(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode())
            assertTrue("the section's texts were read", text(R.string.settings_keep_artists_summary) in texts)
            val offFirst = texts.filter { it.trimStart().startsWith("Off:") }
            assertEquals(emptyList<String>(), offFirst)
        }
}
