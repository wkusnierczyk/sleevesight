package app.sleevesight.app.ui.theme

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * A choice that cannot be used (#1234): with no check mark, its chosen button keeps a strong fill,
 * the text's colour at 38%, so the choice still stands out, while the others keep their light
 * fill; all read as disabled, the chosen one still as selected. Light and dark. Native graphics,
 * for the drawn fills.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DisabledChoiceGroupTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val choices = listOf(3 to "3", 5 to "5", 10 to "10")

    /**
     * The disabled group, 5 chosen, in [dark] or light; then the fills at each button's start,
     * inside its round corner and clear of its label, as their difference from the page's colour
     * (0 to 255, the largest of the channels).
     */
    private fun fills(dark: Boolean): List<Int> {
        var fills = emptyList<Int>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme(darkTheme = dark) {
                        Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
                            ChoiceButtonGroup(
                                choices,
                                5,
                                {},
                                Modifier.width(300.dp).testTag(GROUP),
                                name = "Saver",
                                enabled = false,
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            compose.onNode(hasText("5")).assertIsSelected().assertIsNotEnabled()
            for (other in listOf("3", "10")) compose.onNode(hasText(other)).assertIsNotSelected().assertIsNotEnabled()
            val group = compose.onNodeWithTag(GROUP).fetchSemanticsNode().boundsInRoot
            val image = compose.onNodeWithTag(GROUP).captureToImage().toPixelMap()
            val page = image[0, 0]
            val y = image.height / 2
            val inset = (compose.density.density * 20).toInt()
            fills =
                choices.map { (_, label) ->
                    val button = compose.onNode(hasText(label)).fetchSemanticsNode().boundsInRoot
                    val c = image[(button.left - group.left).toInt() + inset, y]
                    maxOf(abs255(c.red - page.red), abs255(c.green - page.green), abs255(c.blue - page.blue))
                }
        }
        return fills
    }

    private fun abs255(d: Float) = (abs(d) * 255).toInt()

    private fun assertChosenStrong(dark: Boolean) {
        val (three, five, ten) = fills(dark)
        val theme = if (dark) "dark" else "light"
        // 38% of the text's colour against the others' 8%: the old 12% came to about 30 on the page.
        assertTrue("$theme: the chosen fill strong, $five", five >= 70)
        assertTrue("$theme: the others light, $three and $ten", three in 1..40 && ten in 1..40)
        assertTrue("$theme: the chosen one stands out, $five against $three", five >= three + 40)
    }

    @Test
    fun theChosenButtonKeepsAStrongFillInLight() = assertChosenStrong(dark = false)

    @Test
    fun theChosenButtonKeepsAStrongFillInDark() = assertChosenStrong(dark = true)

    private companion object {
        const val GROUP = "group"
    }
}
