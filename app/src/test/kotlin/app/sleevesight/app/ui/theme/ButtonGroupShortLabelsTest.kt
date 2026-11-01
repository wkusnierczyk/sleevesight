package app.sleevesight.app.ui.theme

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A choice group with short labels (#803), as *Music while reading aloud*'s: its full labels where
 * they fit side by side; where they would stack, as at twice the font size, the short ones, in one
 * row; never a stack, even where the short ones do not fit either. A screen reader reads the full
 * label either way. Native graphics, for the labels' real widths on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ButtonGroupShortLabelsTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun show(
        width: Int = GROUP_WIDTH,
        test: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    SleevesightTheme(darkTheme = false) {
                        Box {
                            ChoiceButtonGroup(
                                FULL,
                                selected = 0,
                                onSelect = {},
                                modifier = Modifier.width(width.dp),
                                name = "Music while reading aloud",
                                short = SHORT,
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            test()
        }
    }

    private fun bounds(label: String): Rect =
        compose.onNode(hasText(label) and hasClickAction()).fetchSemanticsNode().boundsInRoot

    private fun assertOneRow(labels: List<String>) {
        val (first, second) = labels.map(::bounds)
        assertEquals("one row", first.top, second.top, 1f)
        assertTrue("side by side", first.right <= second.left)
    }

    @Test
    fun theFullLabelsShowWhereTheyFitSideBySide() =
        show {
            assertOneRow(FULL.map { it.second })
            for (short in SHORT.values) compose.onNodeWithText(short).assertDoesNotExist()
        }

    @Test
    @Config(fontScale = 2f)
    fun whereTheFullLabelsWouldStackTheShortOnesShowInOneRowReadByTheFullOnes() =
        show {
            assertOneRow(SHORT.values.toList())
            for ((_, full) in FULL) compose.onNodeWithText(full).assertDoesNotExist()
            val read =
                compose
                    .onNode(hasText(SHORT.getValue(0)) and hasClickAction())
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsProperties.ContentDescription)
            assertEquals(listOf("Music while reading aloud: Reduce volume"), read)
        }

    @Test
    @Config(fontScale = 2f)
    fun whereEvenTheShortLabelsDoNotFitTheyStayInOneRow() =
        show(width = 120) {
            assertOneRow(SHORT.values.toList())
        }

    @Test
    fun theShortLabelsShowOnlyWhereTheFullOnesWouldStack() {
        val full = listOf(150, 160)
        val short = listOf(60, 60)
        assertEquals(GroupArrangement.EQUAL to false, groupLayout(full, short, 2, 340))
        assertEquals(GroupArrangement.EQUAL to true, groupLayout(full, short, 2, 300))
        assertEquals("never stacked", GroupArrangement.PROPORTIONAL to true, groupLayout(full, short, 2, 100))
        assertEquals("none to show", GroupArrangement.STACKED to false, groupLayout(full, null, 2, 300))
        assertEquals("unbounded", GroupArrangement.EQUAL to false, groupLayout(full, short, 2, null))
    }

    private companion object {
        /** About a settings row's width on a phone. */
        const val GROUP_WIDTH = 340

        val FULL = listOf(0 to "Reduce volume", 1 to "Pause playback")
        val SHORT = mapOf(0 to "Lower", 1 to "Pause")
    }
}
