package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.FlakyDataStore
import app.sleevesight.app.MainActivity
import app.sleevesight.app.MemoryStore
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.WAIT_MS
import app.sleevesight.app.memorySettings
import app.sleevesight.app.now
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.app.ui.settings.KEEP_HISTORY_SLIDER_TAG
import app.sleevesight.app.ui.settings.SettingsScreen
import app.sleevesight.app.ui.settings.SettingsSection
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
import kotlin.time.Duration.Companion.days

/**
 * *Keep history for*'s release (#1126): a release is compared with the value chosen last, stored
 * yet or not, so quick releases while the writes queue up still ask before shortening; and with
 * the value stored itself where it lies between the stops, so a move back to the stop it shows at
 * is a shorter time, asked first, a longer one is stored at once, and no move leaves it as it is.
 */
@RunWith(RobolectricTestRunner::class)
class KeepHistoryReleaseTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    // In memory, over a store whose writes a test can hold back ([held]).
    private val settingsData = FlakyDataStore(MemoryStore())
    private val settings = memorySettings(settingsData)
    private val secrets = SecretStore(MemoryStore(), testCipher(), dispatcher = Dispatchers.Unconfined)
    private val saves = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Holds the settings' writes, once set as the store's gate, until completed. */
    private val held = CompletableDeferred<Unit>()

    @After
    fun close() {
        held.complete(Unit)
        saves.cancel()
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun showHistory(block: (ActivityScenario<MainActivity>) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            showOn(scenario)
            block(scenario)
        }
    }

    /** Shows the settings screen in [scenario]'s activity, *History* opened unless [open] already. */
    private fun showOn(
        scenario: ActivityScenario<MainActivity>,
        open: Boolean = false,
    ) {
        scenario.onActivity { activity ->
            activity.setContent {
                SettingsScreen(settings, secrets, emptyMap(), { CompletableDeferred(true) }, saves) { it }
            }
        }
        if (!open) compose.openSection(SettingsSection.HISTORY)
        compose.waitUntil(WAIT_MS) { shows(app.getString(R.string.settings_keep_history)) }
    }

    /**
     * With the writes held at 90 days: Forever is chosen and queued; then [between] makes the row
     * anew; then 1 year asks, being shorter than Forever, though the store still says 90 days.
     */
    private fun foreverThenOneYearAsks(between: (ActivityScenario<MainActivity>) -> Unit) =
        showHistory { scenario ->
            settingsData.writeGate = held
            moveTo(4)
            between(scenario)
            assertEquals("the store still at 90 days", 90.days, settings.now().historyLifetime)
            moveTo(3)
            assertTrue("1 year after Forever asks", shows("Keep history for 1 year?"))
            compose.onNodeWithText(app.getString(R.string.settings_cancel)).performClick()
            held.complete(Unit)
            compose.waitUntil(WAIT_MS) { settings.now().historyLifetime == null }
            compose.waitForIdle()
            assertEquals("Forever kept", null, settings.now().historyLifetime)
        }

    private fun slider() = compose.onNodeWithTag(KEEP_HISTORY_SLIDER_TAG).performScrollTo()

    private fun spoken(): String? = slider().fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    /** Moves the slider to stop [index] and lets go, as a screen reader's adjustment does. */
    private fun moveTo(index: Int) {
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(index.toFloat()) }
        compose.waitForIdle()
    }

    /** Drags the thumb from stop [from] through [through] and back, letting go there. */
    private fun dragAwayAndBack(
        from: Int,
        through: Int,
    ) {
        slider().performTouchInput {
            val inset = 12.dp.toPx()
            val stop = { i: Int -> Offset(inset + (width - 2 * inset) * i / 4, centerY) }
            down(stop(from))
            moveTo(stop(through))
            moveTo(stop(from))
            up()
        }
        compose.waitForIdle()
    }

    private fun storeHistory(days: Int) = runBlocking { settings.update { it.copy(historyLifetime = days.days) } }

    // Writes held at 90 days: Forever is chosen and queued, then 1 year is shorter than Forever and
    // asks, though the store still says 90 days. Compared with the store, both would be written
    // without asking, and the history shortened from Forever to 1 year.
    @Test
    fun aReleaseWhileAWriteIsQueuedComparesWithTheValueChosenLast() =
        showHistory { _ ->
            settingsData.writeGate = held
            moveTo(4)
            assertTrue("Forever is longer: no question", !shows("Keep history for Forever?"))
            moveTo(3)
            assertTrue("1 year after Forever asks", shows("Keep history for 1 year?"))
            compose.onNodeWithText(app.getString(R.string.settings_cancel)).performClick()
            compose.waitForIdle()
            assertEquals("the thumb back at Forever", "Forever", spoken())

            held.complete(Unit)
            compose.waitUntil(WAIT_MS) { settings.now().historyLifetime == null }
            compose.waitForIdle()
            assertEquals("Forever kept", null, settings.now().historyLifetime)
        }

    // The choice queued is kept with the store, not the row: History closed and opened again makes
    // the row anew.
    @Test
    fun aQueuedChoiceOutlivesHistoryClosedAndOpenedAgain() =
        foreverThenOneYearAsks {
            compose.onNodeWithTag(SettingsSection.HISTORY.panelTag + ":close").performScrollTo().performClick()
            compose.waitForIdle()
            compose.openSection(SettingsSection.HISTORY)
        }

    // ... and the activity made again, as on a rotation.
    @Test
    fun aQueuedChoiceOutlivesTheActivityMadeAgain() =
        foreverThenOneYearAsks { scenario ->
            scenario.recreate()
            // *History* stays open through it, as each section does.
            showOn(scenario, open = true)
        }

    @Test
    fun aStoredValueBetweenTheStopsAsksWhenTheThumbComesBackToItsStop() {
        storeHistory(100)
        showHistory { _ ->
            assertEquals("read as it is", "100 days", spoken())
            dragAwayAndBack(2, 3)
            assertTrue("90 days is shorter than 100", shows("Keep history for 90 days?"))
            compose.onNodeWithText(app.getString(R.string.settings_cancel)).performClick()
            compose.waitForIdle()
            assertEquals(100.days, settings.now().historyLifetime)

            dragAwayAndBack(2, 3)
            compose.onNodeWithText(app.getString(R.string.settings_keep_history_shorten)).performClick()
            compose.waitUntil(WAIT_MS) { settings.now().historyLifetime == 90.days }
        }
    }

    // A drag by touch, as by a finger: the thumb swiped from 90 days to 30 days and let go asks,
    // and nothing is stored before *Shorten*.
    @Test
    fun aSwipeToAShorterTimeAsksAndStoresNothingBeforeShorten() =
        showHistory { _ ->
            slider().performTouchInput {
                val inset = 12.dp.toPx()
                val stop = { i: Int -> Offset(inset + (width - 2 * inset) * i / 4, centerY) }
                swipe(stop(2), stop(1))
            }
            compose.waitForIdle()
            assertTrue(shows("Keep history for 30 days?"))
            assertEquals("nothing stored before the answer", 90.days, settings.now().historyLifetime)
            compose.onNodeWithText(app.getString(R.string.settings_keep_history_shorten)).performClick()
            compose.waitUntil(WAIT_MS) { settings.now().historyLifetime == 30.days }
        }

    // The question stays through the activity made again, as on a rotation, and nothing is stored.
    @Test
    fun theQuestionOutlivesTheActivityMadeAgainAndStoresNothing() =
        showHistory { scenario ->
            moveTo(1)
            assertTrue(shows("Keep history for 30 days?"))
            scenario.recreate()
            showOn(scenario, open = true)
            compose.waitUntil(WAIT_MS) { shows("Keep history for 30 days?") }
            assertEquals("nothing stored", 90.days, settings.now().historyLifetime)
            compose.onNodeWithText(app.getString(R.string.settings_cancel)).performClick()
            compose.waitForIdle()
            assertTrue(!shows("Keep history for 30 days?"))
            assertEquals(90.days, settings.now().historyLifetime)
            assertEquals("90 days", spoken())
        }

    @Test
    fun aStoredValueBetweenTheStopsGoesLongerAtOnce() {
        storeHistory(100)
        showHistory { _ ->
            moveTo(3)
            compose.waitUntil(WAIT_MS) { settings.now().historyLifetime == 365.days }
            assertTrue("no question for a longer time", !shows("Keep history for 1 year?"))
        }
    }

    @Test
    fun aStoredValueBetweenTheStopsStaysWhenTheThumbIsNotMoved() {
        storeHistory(100)
        showHistory { _ ->
            slider().performTouchInput {
                val inset = 12.dp.toPx()
                val stop = Offset(inset + (width - 2 * inset) * 2 / 4, centerY)
                down(stop)
                up()
            }
            compose.waitForIdle()
            assertTrue(!shows("Keep history for 90 days?"))
            assertEquals(100.days, settings.now().historyLifetime)
        }
    }
}
