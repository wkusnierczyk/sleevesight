package app.sleevesight.app.ui.home

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import app.sleevesight.app.R
import kotlin.math.roundToInt

// The mark on Home's buttons that call the AI service (#624), and the alpha it shares with the
// source chips' letters.

/**
 * A small mark's alpha against the text beside it: the source chips' letters (`MB`, `W`) and the
 * **AI** mark, there to be found when looked for, quieter than the words they go with.
 */
internal const val MARK_ALPHA = 0.75f

/**
 * The label of a button whose tap makes a call to the AI service (#624): [label], then a small,
 * dimmed **AI**, set as the source chips' letters are (`labelSmall`, at [MARK_ALPHA] of the
 * label's colour) but after the label, on its baseline. It goes in the button's content slot, in
 * place of the label's `Text`: `Button(onClick) { AiLabel(label) }`. Every button that calls the AI
 * shows its label through it, and no other does: not *Stop writing*, not the split button's arrow.
 *
 * A label too long for its width wraps rather than being cut off; the mark then follows its last
 * line's end, on that line's baseline. Room for the mark is kept at the end of every line, so it
 * never wraps alone. In a right-to-left layout the mark follows the label to its left. The mark is
 * kept within the label's lines, so it never makes the button taller.
 *
 * The mark takes the button's content colour, so a disabled button's mark is dimmed with it. A
 * screen reader reads the button as "<label>, uses AI"; the mark itself is hidden from it. The
 * label stays the button's text, so it can still be found by it. The description and the label are
 * read as one only inside a parent that merges its children's semantics, as a clickable one does
 * (`Button`, `TextButton`, `OutlinedButton`, a `clickable` row): elsewhere a screen reader would read
 * them as two items, so it belongs in a button and nowhere else.
 */
@Composable
internal fun AiLabel(
    label: String,
    modifier: Modifier = Modifier,
) {
    val described = stringResource(R.string.home_uses_ai, label)
    val content = LocalContentColor.current
    // Set as the label is measured, read as it is placed: no state, so no second pass.
    val lines = remember { arrayOfNulls<TextLayoutResult>(1) }
    Layout(
        content = {
            Text(label, onTextLayout = { lines[0] = it })
            Text(
                stringResource(R.string.home_ai_mark),
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = content.alpha * MARK_ALPHA),
                // Said in the button's description; tagged for the tests alone.
                modifier = Modifier.clearAndSetSemantics { testTag = AI_MARK_TAG },
            )
        },
        modifier = modifier.semantics { contentDescription = described },
    ) { (text, ai), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val mark = ai.measure(loose)
        val gap = MARK_GAP.roundToPx()
        val words = text.measure(loose.offset(horizontal = -(mark.width + gap)))
        // Where the last line ends, from the label's start: its right edge left to right, its left
        // edge right to left.
        val layout = lines[0]
        val end =
            if (layout == null) {
                words.width
            } else {
                val last = layout.lineCount - 1
                if (layoutDirection == LayoutDirection.Ltr) {
                    layout.getLineRight(last)
                } else {
                    words.width - layout.getLineLeft(last)
                }.roundToInt()
            }
        // As tall as the label alone: the mark's own line is not let make the button taller.
        val height = words.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        val width = maxOf(words.width, end + gap + mark.width).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, height) {
            val top = (height - words.height) / 2
            words.placeRelative(0, top)
            // After the last line's end, on its baseline, not centred.
            mark.placeRelative(end + gap, top + words[LastBaseline] - mark[FirstBaseline])
        }
    }
}

/** The room between the label and the mark, as between a source chip's letter and its name. */
private val MARK_GAP = 4.dp

/** The test tag of the **AI** mark, on every button that calls the AI service. */
const val AI_MARK_TAG: String = "ai-mark"
