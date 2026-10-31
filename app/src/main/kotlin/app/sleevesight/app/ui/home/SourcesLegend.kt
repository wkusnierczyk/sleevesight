package app.sleevesight.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.CHIP_PADDING_V
import app.sleevesight.app.ui.theme.CHIP_RADIUS
import app.sleevesight.app.ui.theme.chipColours

// The looks of the letters in *Sources*' table (#662), and the table's legend of them (#723).

/** What a source's chip in the table shows: its lines shown or hidden, or none to show. */
internal enum class LetterState { SHOWN, HIDDEN, DIMMED }

/** How a letter looks: its chip's [fill], its [outline] if any, and its letter's colour, [ink]. */
internal class LetterLook(
    val fill: Color,
    val outline: BorderStroke?,
    val ink: Color,
)

/**
 * How a letter looks in each state, the one place that says so, for its chip ([LetterChip]) and
 * its sample in the legend ([LetterSample], #723): [LetterState.SHOWN], filled as a genre marked in
 * its panel is (`chipColours(marked = true)`); [LetterState.HIDDEN], a solid outline in the text's
 * colour, a little heavier ([HIDDEN_OUTLINE]), and no fill; [LetterState.DIMMED], a faint hairline
 * (`outlineVariant`), no fill, the letter quieter (still 4.5:1 on the panel, `onSurfaceVariant`).
 */
@Composable
internal fun LetterState.look(): LetterLook {
    val colors = MaterialTheme.colorScheme
    val (marked, onMarked) = chipColours(marked = true)
    return when (this) {
        LetterState.SHOWN -> LetterLook(marked, null, onMarked)
        LetterState.HIDDEN ->
            LetterLook(Color.Transparent, BorderStroke(HIDDEN_OUTLINE, colors.onSurface), colors.onSurface)
        LetterState.DIMMED ->
            LetterLook(Color.Transparent, BorderStroke(HAIRLINE, colors.outlineVariant), colors.onSurfaceVariant)
    }
}

/**
 * The table's legend (#723), always under it while it has rows, outside the developer mark: a line
 * of the letters' three looks, each a small blank sample ([LetterSample]) and its word (*Shown*,
 * *Hidden*, *No results*), wrapping as a [FlowRow], and under it *Tap a source to show or hide its
 * results.*; in the table's status lines' style. A screen reader skips it, every node of it hidden
 * (as a decorative cell is in the key table): each letter already says its state as a switch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Legend() {
    val style = MaterialTheme.typography.bodySmall
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    val hidden = Modifier.semantics { hideFromAccessibility() }
    // The samples scaled as the words beside them are, so in a large font they keep to them.
    val scale = with(LocalDensity.current) { style.fontSize.toDp() / style.fontSize.value.dp }
    Column(
        modifier = Modifier.testTag(SOURCES_LEGEND_TAG).then(hidden),
        verticalArrangement = Arrangement.spacedBy(LEGEND_GAP),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(LEGEND_ITEM_GAP),
            verticalArrangement = Arrangement.spacedBy(LEGEND_GAP),
            modifier = hidden,
        ) {
            for ((state, word) in LEGEND_WORDS) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(LEGEND_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = hidden,
                ) {
                    LetterSample(state, scale, hidden)
                    Text(stringResource(word), style = style, color = colour, modifier = hidden)
                }
            }
        }
        Text(stringResource(R.string.home_sources_legend_tap), style = style, color = colour, modifier = hidden)
    }
}

/** The legend's items, in order: each look, and its word. */
private val LEGEND_WORDS =
    listOf(
        LetterState.SHOWN to R.string.home_sources_legend_shown,
        LetterState.HIDDEN to R.string.home_sources_legend_hidden,
        LetterState.DIMMED to R.string.home_sources_legend_none,
    )

/**
 * A letter's look in [state] as a small blank sample for the legend (#723): [LetterChip]'s fill and
 * outline ([look]), [SAMPLE_WIDTH] by [SAMPLE_HEIGHT] times [scale], its corners the chips'
 * ([CHIP_RADIUS]) scaled to it as it is to a letter's chip (its text's line and [CHIP_PADDING_V]
 * above and below), so a pill at the default font size, as the letters are, and rounded as they are
 * in a large one; no letter, and no button. [modifier] goes first (it hides the sample's node).
 */
@Composable
private fun LetterSample(
    state: LetterState,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val look = state.look()
    val height = SAMPLE_HEIGHT * scale
    val line =
        with(LocalDensity.current) {
            MaterialTheme.typography.labelLarge.lineHeight
                .toDp()
        }
    val shape = RoundedCornerShape(CHIP_RADIUS * (height / (line + CHIP_PADDING_V * 2)))
    Box(
        modifier
            .size(SAMPLE_WIDTH * scale, height)
            .background(look.fill, shape)
            .then(look.outline?.let { Modifier.border(it, shape) } ?: Modifier),
    )
}

// A hidden chip's outline, and a dimmed one's hairline (as a chip frame's).
private val HIDDEN_OUTLINE = 1.5.dp
private val HAIRLINE = 1.dp

// The legend's samples at the default font size; the space between its items, and between a
// sample and its word, its wrapped lines and its two lines.
private val SAMPLE_WIDTH = 22.dp
private val SAMPLE_HEIGHT = 14.dp
private val LEGEND_ITEM_GAP = 16.dp
private val LEGEND_GAP = 6.dp

/** The test tag of the table's legend (#723). */
const val SOURCES_LEGEND_TAG: String = "sources-legend"
