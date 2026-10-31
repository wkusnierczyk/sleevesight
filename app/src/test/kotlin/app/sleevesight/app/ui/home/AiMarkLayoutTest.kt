package app.sleevesight.app.ui.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getAlignmentLinePosition
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import app.sleevesight.app.MainActivity
import app.sleevesight.app.R
import app.sleevesight.core.settings.WriteAutomatically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * How [AiLabel] lays out a label that wraps, and right to left (#624): the mark after the last
 * line's end, on its baseline; the split button's halves of one height whatever the label, the
 * arrow never squeezed below 48 dp. Text measured as on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AiMarkLayoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun show(
        content: @Composable () -> Unit,
        block: () -> Unit,
    ) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { Column(Modifier.padding(16.dp)) { content() } } }
            block()
        }
    }

    // The label's text, in the tree as drawn, inside the node tagged [tag].
    private fun label(
        text: String,
        tag: String,
    ) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)

    private fun SemanticsNodeInteraction.lines(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private val mark get() = compose.onNodeWithTag(AI_MARK_TAG, useUnmergedTree = true)

    // A label as long as a genre's question (#523), at twice the font size on a narrow phone.
    @Test
    @Config(qualifiers = "w240dp-h640dp", fontScale = 2f)
    fun aLongLabelWrapsTheMarkFollowsItsLastLineAndTheHalvesStayOneHeight() {
        val long = "See Settings for details"
        show({
            WriteSplitButton(
                CardOffer(R.string.home_write_mode_details, enabled = true),
                {},
                {},
                WritingChoice(WriteAutomatically.ON_EVERY_TRACK_CHANGE),
            )
        }) {
            val text = label(long, WRITE_TAG)
            val lines = text.lines()
            assertTrue("wraps", lines.lineCount > 1)
            val last = lines.lineCount - 1
            val box = text.getUnclippedBoundsInRoot()
            val markBox = mark.getUnclippedBoundsInRoot()
            val density = compose.density.density
            // On the last line's baseline, after its last word.
            val lastBaseline = box.top.value + lines.getLineBaseline(last) / density
            val markBaseline = (markBox.top + mark.getAlignmentLinePosition(FirstBaseline)).value
            assertEquals(lastBaseline, markBaseline, TOLERANCE)
            assertTrue("after the last word", markBox.left.value >= box.left.value + lines.getLineRight(last) / density)
            // Inside the button.
            val write = compose.onNodeWithTag(WRITE_TAG).getUnclippedBoundsInRoot()
            assertTrue("inside the button", markBox.right <= write.right)
            // The halves of one height, the arrow 48 dp wide.
            val arrow = compose.onNodeWithTag(WRITE_MODE_TAG).assertWidthIsAtLeast(48.dp).getUnclippedBoundsInRoot()
            assertEquals((write.bottom - write.top).value, (arrow.bottom - arrow.top).value, TOLERANCE)
            assertEquals(write.top.value, arrow.top.value, TOLERANCE)
        }
    }

    @Test
    fun rightToLeftTheMarkFollowsTheLabelToItsLeft() {
        val text = "Write insight"
        show({
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Button({}, Modifier.testTag(BUTTON)) { AiLabel(text) }
            }
        }) {
            val box = label(text, BUTTON).getUnclippedBoundsInRoot()
            val markBox = mark.getUnclippedBoundsInRoot()
            assertTrue("to the label's left", markBox.right <= box.left)
        }
    }

    private companion object {
        // Half a dp: each edge or baseline rounds to whole pixels.
        const val TOLERANCE = 0.5f
        const val BUTTON = "button"
    }
}
