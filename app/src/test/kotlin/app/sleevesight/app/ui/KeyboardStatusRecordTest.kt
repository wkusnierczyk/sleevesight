package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.ui.keyboard.KeyboardStatus
import app.sleevesight.app.ui.keyboard.waitingMessage
import app.sleevesight.app.ui.logo.SPINNING_RECORD_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * *Type on a computer*'s status while it starts or waits for the computer (#785): the turning
 * record before the message, beside its first line, and *Stop* still at the right; the other
 * states, *Connected* among them, without it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
class KeyboardStatusRecordTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    private fun records(): List<Rect> =
        compose
            .onAllNodesWithTag(SPINNING_RECORD_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }

    @Test
    fun whileItStartsOrWaitsTheRecordGoesBeforeTheMessageAndStopStaysAtTheRight() {
        val connected = app.getString(R.string.keyboard_connected)
        val starting = app.getString(R.string.keyboard_starting)
        var shown by mutableStateOf("starting")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    when (shown) {
                        "starting" -> KeyboardStatus(starting, busy = true) {}
                        "waiting" ->
                            KeyboardStatus(
                                waitingMessage(showName = true, advertised = "Pixel 9"),
                                busy = true,
                            ) {}
                        else -> KeyboardStatus(connected) {}
                    }
                }
            }
            for ((case, line) in listOf(
                "starting" to starting,
                "waiting" to app.getString(R.string.keyboard_waiting_named, "Pixel 9"),
            )) {
                shown = case
                compose.waitForIdle()
                val message = compose.onNodeWithText(line).fetchSemanticsNode().boundsInRoot
                val stop =
                    compose
                        .onNodeWithText(
                            app.getString(R.string.keyboard_stop),
                        ).fetchSemanticsNode()
                        .boundsInRoot
                val disc = records().single()
                assertEquals(case, 8f * compose.density.density, message.left - disc.right, 1f)
                assertTrue(case, disc.center.y > message.top && disc.center.y < message.top + message.height / 2 + 1f)
                assertTrue(case, stop.left >= message.right)
            }
            shown = "connected"
            compose.waitForIdle()
            compose.onNodeWithText(connected).assertExists()
            assertEquals(emptyList<Rect>(), records())
        }
    }
}
