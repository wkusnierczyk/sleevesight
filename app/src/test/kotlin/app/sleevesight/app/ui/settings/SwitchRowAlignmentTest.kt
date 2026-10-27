package app.sleevesight.app.ui.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.ui.theme.SleevesightTheme
import app.sleevesight.core.settings.FontChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A settings row's mark and switch (#613): centred on the first line of the row's name, not on the
 * whole row, so that they stay level with the name however long the description under it; in both
 * fonts and at twice the font size. A row with a name only is laid out as when they were centred on
 * the row, and every row keeps its 48 dp target and is read as before. Native graphics, for the
 * text's real lines on a phone's width and density.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SwitchRowAlignmentTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /** One row to show: its name, its description, and whether it starts with a mark. */
    private data class Spec(
        val title: String,
        val summary: String?,
        val mark: Boolean = true,
        val font: FontChoice = FontChoice.APP_FONT,
    )

    private var spec by mutableStateOf(Spec(NAME, LONG_SUMMARY))

    private fun show(test: () -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val shown = spec
                    SleevesightTheme(darkTheme = false, font = shown.font) {
                        SwitchRow(
                            title = shown.title,
                            summary = shown.summary,
                            checked = true,
                            leading =
                                if (shown.mark) {
                                    { Box(Modifier.size(ROW_MARK).background(Color.Gray)) }
                                } else {
                                    null
                                },
                            onChange = {},
                        )
                    }
                }
            }
            compose.waitForIdle()
            test()
        }
    }

    private fun showing(next: Spec) {
        spec = next
        compose.waitForIdle()
    }

    private val density get() = compose.density.density

    private fun bounds(tag: String): Rect =
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

    /** The shown row's name's text node, and its text as laid out. */
    private fun titleLayout(): Pair<SemanticsNode, TextLayoutResult> {
        val node = compose.onNodeWithText(spec.title, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return node to layouts.single()
    }

    /** The middle of the first line of the shown row's name, in pixels from the top of the screen. */
    private fun firstLineMiddle(): Float {
        val (node, layout) = titleLayout()
        return node.boundsInRoot.top + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2
    }

    /** The shown row's mark (when it has one) and switch are centred on its name's first line. */
    private fun assertLevelWithTheName() {
        val line = firstLineMiddle()
        val switch = bounds(ROW_SWITCH_TAG)
        assertEquals("${spec.font}: the switch's middle is the name's first line's", line, switch.center.y, density)
        if (spec.mark) {
            val mark = bounds(ROW_LEADING_TAG)
            assertEquals("${spec.font}: the mark's middle is the name's first line's", line, mark.center.y, density)
            assertEquals("${spec.font}: the mark is 40 dp high", ROW_MARK.value, mark.height / density, 0.5f)
        }
    }

    /** The shown row is one switch read as before: its name, then its description; 48 dp or more high. */
    private fun assertReadAsBefore() {
        val row = compose.onNode(isToggleable() and hasText(spec.title)).fetchSemanticsNode()
        val read = row.config[SemanticsProperties.Text].map { it.text }
        assertEquals(listOfNotNull(spec.title, spec.summary), read)
        assertEquals(Role.Switch, row.config.getOrNull(SemanticsProperties.Role))
        assertEquals(ToggleableState.On, row.config.getOrNull(SemanticsProperties.ToggleableState))
        val height = row.boundsInRoot.height / density
        assertTrue("the row's target is 48 dp or more: $height dp", height >= 48f - 0.5f)
    }

    // Centred on the whole row, the switch and the mark sat by the middle of the description, lines
    // below the name.
    @Test
    fun aRowWithADescriptionHasItsMarkAndSwitchLevelWithItsName() =
        show {
            for (font in FontChoice.entries) {
                showing(Spec(NAME, LONG_SUMMARY, font = font))
                assertLevelWithTheName()
                assertReadAsBefore()
                showing(Spec(NAME, LONG_SUMMARY, mark = false, font = font))
                assertLevelWithTheName()
                assertReadAsBefore()
            }
        }

    @Test
    fun aNameOnTwoLinesHasItsMarkAndSwitchLevelWithTheFirst() =
        show {
            showing(Spec(LONG_NAME, null))
            assertTrue("the name takes more than a line", titleLayout().second.lineCount > 1)
            assertLevelWithTheName()
            assertReadAsBefore()
        }

    // As before #613: the row as high as its mark (or switch), with the name centred on it.
    @Test
    fun aRowWithANameOnlyIsLaidOutAsBefore() =
        show {
            for (font in FontChoice.entries) {
                showing(Spec(NAME, null, font = font))
                assertLevelWithTheName()
                assertReadAsBefore()
                val row = compose.onNode(isToggleable() and hasText(NAME)).fetchSemanticsNode().boundsInRoot
                val mark = bounds(ROW_LEADING_TAG)
                assertEquals("$font: the row is its mark, 8 dp above and below", 56f, row.height / density, 0.5f)
                assertEquals("$font: the mark in the row's middle", row.center.y, mark.center.y, density)

                showing(Spec(NAME, null, mark = false, font = font))
                val plain = compose.onNode(isToggleable() and hasText(NAME)).fetchSemanticsNode().boundsInRoot
                val switch = bounds(ROW_SWITCH_TAG)
                assertEquals("$font: the row is its switch, 8 dp above and below", 48f, plain.height / density, 0.5f)
                assertEquals("$font: the switch in the row's middle", plain.center.y, switch.center.y, density)
            }
        }

    // At twice the size, a line of the name is taller than the switch and nearly as tall as the
    // mark, and the description runs to more lines.
    @Test
    @Config(fontScale = 2f)
    fun atTwiceTheFontSizeTheMarkAndSwitchStayLevelWithTheName() =
        show {
            for (font in FontChoice.entries) {
                showing(Spec(NAME, LONG_SUMMARY, font = font))
                assertLevelWithTheName()
                assertReadAsBefore()
                showing(Spec(LONG_NAME, LONG_SUMMARY, mark = false, font = font))
                assertLevelWithTheName()
                assertReadAsBefore()
            }
        }

    private companion object {
        const val NAME = "Wikipedia"
        const val LONG_NAME = "A setting whose name is long enough to run past the end of its first line on a phone"
        const val LONG_SUMMARY =
            "A description long enough to take several lines under the name, as the sources' and the " +
                "AI services' descriptions do, so that the middle of the row is well below the name."
    }
}
