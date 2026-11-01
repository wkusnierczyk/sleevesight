package app.sleevesight.app.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.app.SleevesightApplication
import app.sleevesight.app.ui.keyboard.KeyboardStatus
import app.sleevesight.app.ui.keyboard.waitingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * *Type on a computer*'s *Stop* (#785), as picked in the mock-up: an outlined button, with a
 * button's default padding and its outline, at the right of the message and centred on it, whether the message
 * takes one line or several, with the turning record or without; a tap still stops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardStopButtonTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<SleevesightApplication>()

    @Test
    fun stopIsAnOutlinedButtonCentredOnTheMessageOnOneLineOrSeveral() {
        val stopText = app.getString(R.string.keyboard_stop)
        val starting = app.getString(R.string.keyboard_starting)
        val connected = app.getString(R.string.keyboard_connected)
        val waiting = app.getString(R.string.keyboard_waiting_named, "Pixel 9")
        var shown by mutableStateOf("starting")
        var stops = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    when (shown) {
                        "starting" -> KeyboardStatus(starting, busy = true) { stops++ }
                        "waiting" ->
                            KeyboardStatus(
                                waitingMessage(showName = true, advertised = "Pixel 9"),
                                busy = true,
                            ) { stops++ }
                        else -> KeyboardStatus(connected) { stops++ }
                    }
                }
            }
            val density = compose.density.density
            val padding =
                with(ButtonDefaults.ContentPadding) {
                    calculateLeftPadding(LayoutDirection.Ltr) + calculateRightPadding(LayoutDirection.Ltr)
                }.value * density
            val lines = mutableMapOf<String, Float>()
            for ((case, line) in listOf("starting" to starting, "waiting" to waiting, "connected" to connected)) {
                shown = case
                compose.waitForIdle()
                val message = compose.onNodeWithText(line).fetchSemanticsNode().boundsInRoot
                val button =
                    compose
                        .onNodeWithText(stopText)
                        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                val stop: Rect = button.fetchSemanticsNode().boundsInRoot
                val label = compose.onNodeWithText(stopText, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                // An outlined button's default padding, not a text button's, which is half of it.
                assertEquals(case, padding, stop.width - label.width, 1f)
                // Its outline: at its left edge, half way down, a colour the inside, a little in, does not have.
                val picture = button.captureToImage().asAndroidBitmap()
                val middle = picture.height / 2
                assertNotEquals(case, picture.getPixel(1, middle), picture.getPixel((6 * density).toInt(), middle))
                assertTrue(case, stop.left >= message.right)
                assertEquals(case, message.center.y, stop.center.y, density)
                lines[case] = message.height
                stops = 0
                button.performClick()
                compose.waitForIdle()
                assertEquals(case, 1, stops)
            }
            // *Starting Bluetooth…* takes one line; the waiting message, with the record, and *Connected*'s,
            // without, wrap.
            assertTrue(lines.toString(), lines.getValue("waiting") >= 2 * lines.getValue("starting"))
            assertTrue(lines.toString(), lines.getValue("connected") >= 2 * lines.getValue("starting"))
        }
    }
}
