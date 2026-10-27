package app.sleevesight.app.ui.home

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import app.sleevesight.app.R

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
 * shows its label through it, and no other does: not *Stop*, not the split button's arrow.
 *
 * The mark takes the button's content colour, so a disabled button's mark is dimmed with it. A
 * screen reader reads the button as "<label>, uses AI"; the mark itself is hidden from it. The
 * label stays the button's text, so it can still be found by it.
 */
@Composable
internal fun AiLabel(
    label: String,
    modifier: Modifier = Modifier,
) {
    val described = stringResource(R.string.home_uses_ai, label)
    val content = LocalContentColor.current
    Layout(
        content = {
            Text(label)
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
        // As tall as the label alone: the mark's own line is not let make the button taller.
        val height = words.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        val width = (words.width + gap + mark.width).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, height) {
            val top = (height - words.height) / 2
            words.place(0, top)
            // On the label's baseline, not centred.
            mark.place(words.width + gap, top + words[FirstBaseline] - mark[FirstBaseline])
        }
    }
}

/** The room between the label and the mark, as between a source chip's letter and its name. */
private val MARK_GAP = 4.dp

/** The test tag of the **AI** mark, on every button that calls the AI service. */
const val AI_MARK_TAG: String = "ai-mark"
