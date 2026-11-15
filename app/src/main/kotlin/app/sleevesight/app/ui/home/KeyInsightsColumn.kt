package app.sleevesight.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import app.sleevesight.app.R
import app.sleevesight.core.model.NowPlaying

// The chips under *Key insights* as one column (#1286): the rows' buttons and *Sources*' chip, one
// width, the **AI** mark at the same place at each row button's end.

/**
 * The label of a chip in a column of chips as wide as the widest ([PanelChip] with a width,
 * [chipColumnWidth]; #1286): with [ai], the small, dimmed **AI** mark at the chip's end, in the same
 * place on every chip of the column, on the name's last baseline, and [name] centred in the room
 * left of it ([MARK_GAP] before the mark); without it, [name] centred in the whole chip. The widest
 * chip's name fills its room, so its mark follows it at [MARK_GAP] as [AiLabel]'s does. In a
 * right-to-left layout the mark is at the chip's left end. A name too long for its room wraps, the
 * mark on its last line. In [colour], the mark at [MARK_ALPHA] of it; the chip's description says
 * "uses AI", so the mark itself is hidden from a screen reader. As tall as the name alone.
 */
@Composable
internal fun ColumnChipLabel(
    name: String,
    colour: Color,
    ai: Boolean,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            Text(
                name,
                Modifier.testTag(CHIP_LABEL_TAG),
                style = MaterialTheme.typography.labelLarge,
                color = colour,
                textAlign = TextAlign.Center,
            )
            if (ai) {
                Text(
                    stringResource(R.string.home_ai_mark),
                    style = MaterialTheme.typography.labelSmall,
                    color = colour.copy(alpha = colour.alpha * MARK_ALPHA),
                    // Said in the chip's description; tagged for the tests alone.
                    modifier = Modifier.clearAndSetSemantics { testTag = AI_MARK_TAG },
                )
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val mark = measurables.getOrNull(1)?.measure(loose)
        // The mark and the gap before it, kept at the end.
        val end = mark?.let { it.width + MARK_GAP.roundToPx() } ?: 0
        val label = measurables[0].measure(loose.offset(horizontal = -end))
        val width =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                (label.width + end).coerceAtLeast(constraints.minWidth)
            }
        val height = label.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            val top = (height - label.height) / 2
            label.placeRelative(((width - end - label.width) / 2).coerceAtLeast(0), top)
            mark?.placeRelative(width - mark.width, top + label[LastBaseline] - mark[FirstBaseline])
        }
    }
}

/**
 * How wide every chip under *Key insights* is (#894, #1286): the buttons of [rows] and *Sources*'
 * chip under them, as wide as the widest of their labels as a chip draws it, a row's kind with the
 * **AI** mark after it while it carries one ([ai], *Show insights*), *Sources* without; that chip fits
 * its content exactly. From the rows the track has, so a pop track's *Song*, *Artist* and *Album*
 * take a narrower column than a classical one's *Composer* or *Performer*, and whichever rows are
 * open, so nothing moves on opening. Measured in the font as drawn, so it follows the font size.
 */
@Composable
internal fun keyInsightsWidth(
    rows: List<ItemRow>,
    ai: Boolean,
): Dp =
    chipColumnWidth(
        rows.map { itemKind(it.item, it) },
        mark = ai,
        unmarked = listOf(stringResource(R.string.home_sources_title)),
    )

/** The rows under *Key insights* for [header], the track shown ([itemRows]), as Home has them. */
@Composable
internal fun homeItemRows(
    shown: Showing,
    header: NowPlaying,
    actions: NowPlayingActions,
): List<ItemRow> {
    val normalized = actions.normalize(header)
    return itemRows(header, normalized, remember(shown.track, normalized) { splitOf(shown.track, normalized) })
}

/** The test tag of a column chip's name ([ColumnChipLabel]), found in the unmerged tree. */
const val CHIP_LABEL_TAG: String = "chip-label"
