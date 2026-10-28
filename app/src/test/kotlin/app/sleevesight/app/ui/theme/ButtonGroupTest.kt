package app.sleevesight.app.ui.theme

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil

/**
 * The connected button group (#637), [ChoiceButtonGroup] and [ActionButtonGroup]: one group of
 * radio buttons, the chosen one selected, or plain buttons each enabled or not; short labels side
 * by side, each as wide as the others, in a row with a 48 dp target; labels that do not fit side by
 * side, as at twice the font size, one under another, full width, none of their text cut. Native
 * graphics, for the labels' real widths on a phone's width and density.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ButtonGroupTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private enum class Theme { SYSTEM, LIGHT, DARK }

    private val themes = listOf(Theme.SYSTEM to "System", Theme.LIGHT to "Light", Theme.DARK to "Dark")

    private var selected by mutableStateOf(Theme.SYSTEM)
    private val chosen = mutableListOf<Theme>()

    /** [themes] as a choice, [width] wide, which a tap records in [chosen] and selects. */
    private fun showChoice(
        choices: List<Pair<Theme, String>> = themes,
        width: Int = GROUP_WIDTH,
        test: () -> Unit,
    ) = show(test) {
        val onSelect: (Theme) -> Unit = {
            chosen += it
            selected = it
        }
        ChoiceButtonGroup(choices, selected, onSelect, Modifier.width(width.dp).testTag(GROUP))
    }

    private fun show(
        test: () -> Unit,
        content: @Composable () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent { SleevesightTheme(darkTheme = false) { Box { content() } } }
            }
            compose.waitForIdle()
            test()
        }
    }

    private val density get() = compose.density.density

    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun bounds(label: String): Rect = button(label).fetchSemanticsNode().boundsInRoot

    private fun groupBounds(): Rect = compose.onNodeWithTag(GROUP).fetchSemanticsNode().boundsInRoot

    /**
     * [label] is shown whole, on one line: its text node as wide as the label needs, and within its
     * button. Not `hasVisualOverflow`: the result `GetTextLayoutResult` returns lays its paragraph
     * out at the whole width offered, so it reads true for a label narrower than that
     * (`NowPlayingScreenTest.noTagOverflowsItsColumn`).
     */
    private fun assertWhole(label: String) {
        val node = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        assertEquals("$label on one line", 1, layout.lineCount)
        val needed = ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth).toInt()
        assertTrue("$label is ${node.size.width} px of $needed", node.size.width >= needed)
        val text = node.boundsInRoot
        val button = bounds(label)
        assertTrue("$label within its button: $text in $button", button.inflate(1f).let { text.intersect(it) == text })
    }

    @Test
    fun aChoiceIsOneGroupOfRadioButtonsTheChosenOneSelected() =
        showChoice {
            val group = compose.onNodeWithTag(GROUP).fetchSemanticsNode()
            assertTrue("one selectable group", SemanticsProperties.SelectableGroup in group.config)
            for ((value, label) in themes) {
                val node = button(label).fetchSemanticsNode()
                assertEquals(label, Role.RadioButton, node.config.getOrNull(SemanticsProperties.Role))
                assertEquals(label, value == Theme.SYSTEM, node.config.getOrNull(SemanticsProperties.Selected))
            }
        }

    @Test
    fun aTapChoosesTheButtonsValueAndTheGroupFollowsTheValueGiven() =
        showChoice {
            button("Dark").performClick()
            compose.waitForIdle()
            assertEquals(listOf(Theme.DARK), chosen)
            button("Dark").assertIsSelected()
            button("System").assertIsNotSelected()

            // Changed elsewhere, as Home's theme button will change *Theme* (#636).
            selected = Theme.LIGHT
            compose.waitForIdle()
            button("Light").assertIsSelected()
            button("Dark").assertIsNotSelected()
            assertEquals("no tap, no choice", listOf(Theme.DARK), chosen)
        }

    @Test
    fun shortLabelsShareOneRowEquallyWithA48dpTarget() =
        showChoice {
            val all = themes.map { bounds(it.second) }
            assertTrue("one row: ${all.map { it.top }}", all.all { it.top == all.first().top })
            assertTrue("in order", all.zipWithNext().all { (a, b) -> a.right <= b.left })
            for (b in all) assertEquals("as wide as the others", all.first().width, b.width, 1f)
            assertTrue("drawn 32 dp high", all.all { it.height / density >= 32f - 0.5f })
            assertTrue("the row is 48 dp high or more", groupBounds().height / density >= 48f - 0.5f)
            // Full width, the gaps included.
            assertEquals(GROUP_WIDTH.toFloat(), (all.last().right - all.first().left) / density, 1f)
        }

    @Test
    fun theButtonsKeepTheirWidthsWhicheverIsChosen() =
        showChoice(choices = LONG_AND_SHORT) {
            val before = LONG_AND_SHORT.map { bounds(it.second) }
            button(LONG_AND_SHORT.last().second).performClick()
            compose.waitForIdle()
            val after = LONG_AND_SHORT.map { bounds(it.second) }
            assertEquals(before, after)
        }

    @Test
    fun labelsTooLongToShareTheWidthEquallyEachGetWhatTheyNeedInOneRow() =
        showChoice(choices = LONG_AND_SHORT, width = 300) {
            val (long, short) = LONG_AND_SHORT.map { bounds(it.second) }
            assertEquals("one row", long.top, short.top)
            assertTrue("the longer label's button is wider", long.width > short.width)
            for ((_, label) in LONG_AND_SHORT) assertWhole(label)
        }

    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeLabelsThatDoNotFitStackFullWidthAndNothingIsCut() =
        showChoice(choices = LONG_AND_SHORT) {
            val (long, short) = LONG_AND_SHORT.map { bounds(it.second) }
            assertTrue("one under the other", long.bottom <= short.top)
            assertEquals("aligned", long.left, short.left, 1f)
            for (b in listOf(long, short)) {
                assertEquals("full width", GROUP_WIDTH.toFloat(), b.width / density, 1f)
                assertTrue("48 dp or more high", b.height / density >= 48f - 0.5f)
            }
            for ((_, label) in LONG_AND_SHORT) assertWhole(label)
            button(LONG_AND_SHORT.last().second).performClick()
            compose.waitForIdle()
            button(LONG_AND_SHORT.last().second).assertIsSelected()
        }

    @Test
    fun actionsArePlainButtonsEachEnabledOrNotAndNeverSelected() {
        val tapped = mutableListOf<String>()
        val actions =
            listOf(
                GroupAction("Show less", enabled = false, tag = "less") { tapped += "less" },
                GroupAction("Show more") { tapped += "more" },
                GroupAction("Show all") { tapped += "all" },
            )
        show({
            for (action in actions) {
                val node = button(action.label).fetchSemanticsNode()
                assertEquals(action.label, Role.Button, node.config.getOrNull(SemanticsProperties.Role))
                assertNull("${action.label} is never selected", node.config.getOrNull(SemanticsProperties.Selected))
            }
            assertFalse(
                "not a choice",
                SemanticsProperties.SelectableGroup in compose.onNodeWithTag(GROUP).fetchSemanticsNode().config,
            )
            // A tag given lands on its button.
            compose.onNodeWithTag("less").assertTextEquals("Show less").assertIsNotEnabled()
            button("Show less").assertIsNotEnabled().performClick()
            button("Show more").assertIsEnabled().performClick()
            button("Show all").performClick()
            compose.waitForIdle()
            assertEquals(listOf("more", "all"), tapped)
            val all = actions.map { bounds(it.label) }
            assertTrue("one row", all.all { it.top == all.first().top })
        }) {
            ActionButtonGroup(actions, Modifier.width(GROUP_WIDTH.dp).testTag(GROUP))
        }
    }

    // Right to left, as in Arabic or Hebrew: the first choice at the right, the row mirrored.
    @Test
    fun rightToLeftTheFirstChoiceIsRightmost() =
        show({
            val all = themes.map { bounds(it.second) }
            assertTrue("one row", all.all { it.top == all.first().top })
            assertTrue("from the right: ${all.map { it.left }}", all.zipWithNext().all { (a, b) -> a.left >= b.right })
        }) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                ChoiceButtonGroup(themes, selected, { selected = it }, Modifier.width(GROUP_WIDTH.dp).testTag(GROUP))
            }
        }

    // The first button's round corners are at its start: the right, right to left.
    @Test
    fun rightToLeftTheCornersAreMirrored() {
        val first = groupShape(0, 3, selected = false, stacked = false)
        val size = Size(100f, 32f)
        val unit = Density(1f)
        val rtl = (first.createOutline(size, LayoutDirection.Rtl, unit) as Outline.Rounded).roundRect
        val ltr = (first.createOutline(size, LayoutDirection.Ltr, unit) as Outline.Rounded).roundRect
        assertEquals(16f, rtl.topRightCornerRadius.x, 0.01f)
        assertEquals(16f, rtl.bottomRightCornerRadius.x, 0.01f)
        assertEquals(4f, rtl.topLeftCornerRadius.x, 0.01f)
        assertEquals(16f, ltr.topLeftCornerRadius.x, 0.01f)
        assertEquals(4f, ltr.topRightCornerRadius.x, 0.01f)
    }

    @Test
    fun theArrangementIsEqualThenProportionalThenStacked() {
        assertEquals(GroupArrangement.EQUAL, groupArrangement(listOf(100, 80, 90), gap = 2, room = 304))
        assertEquals(GroupArrangement.PROPORTIONAL, groupArrangement(listOf(200, 80), gap = 2, room = 300))
        assertEquals(GroupArrangement.STACKED, groupArrangement(listOf(200, 120), gap = 2, room = 300))
        assertEquals(GroupArrangement.EQUAL, groupArrangement(listOf(149, 149), gap = 2, room = 300))
        assertEquals(GroupArrangement.PROPORTIONAL, groupArrangement(listOf(150, 140), gap = 2, room = 300))
        assertEquals(GroupArrangement.STACKED, groupArrangement(listOf(150, 149), gap = 2, room = 300))
    }

    @Test
    fun theOuterCornersAreRoundTheInnerSmallAndTheChosenRoundAllRound() {
        val o = GROUP_OUTER_CORNER
        val i = GROUP_INNER_CORNER
        assertEquals(RoundedCornerShape(o, i, i, o), groupShape(0, 3, selected = false, stacked = false))
        assertEquals(RoundedCornerShape(i), groupShape(1, 3, selected = false, stacked = false))
        assertEquals(RoundedCornerShape(i, o, o, i), groupShape(2, 3, selected = false, stacked = false))
        assertEquals(RoundedCornerShape(o), groupShape(1, 3, selected = true, stacked = false))
        assertEquals(RoundedCornerShape(o, o, i, i), groupShape(0, 2, selected = false, stacked = true))
        assertEquals(RoundedCornerShape(i, i, o, o), groupShape(1, 2, selected = false, stacked = true))
        assertEquals(RoundedCornerShape(o), groupShape(0, 1, selected = false, stacked = false))
    }

    private companion object {
        const val GROUP = "group"

        /** About a settings row's width on a phone. */
        const val GROUP_WIDTH = 340

        /** *Layout*'s choices: at twice the font size, too long to share the row. */
        val LONG_AND_SHORT = listOf(Theme.SYSTEM to "Expandable boxes", Theme.DARK to "Pages")
    }
}
