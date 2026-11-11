package app.sleevesight.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.CHIP_PADDING_V
import app.sleevesight.app.ui.theme.CHIP_RADIUS
import app.sleevesight.app.ui.theme.chipColours
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import kotlin.math.max
import kotlin.math.min

// The looks of the letters in *Sources*' table (#662), and the table's legend of them (#723, #951).

/**
 * What a source's chip in the table shows: its lines shown, its lines shown that are all unverified
 * (#951), its lines hidden, or none to show.
 */
internal enum class LetterState { SHOWN, UNVERIFIED, HIDDEN, DIMMED }

/** How a letter looks: its chip's [fill], its [outline] if any, and its letter's colour, [ink]. */
internal class LetterLook(
    val fill: Color,
    val outline: BorderStroke?,
    val ink: Color,
)

/**
 * How a letter looks in each state, the one place that says so, for its chip ([LetterChip]) and
 * its sample in the legend ([LetterSample], #723): [LetterState.SHOWN], filled as a genre marked in
 * its panel is (`chipColours(marked = true)`); [LetterState.UNVERIFIED], filled mid grey (`outline`,
 * #951), darkened if need be so its letter is 4.5:1 on it ([unverifiedColours]);
 * [LetterState.HIDDEN], a solid outline in the text's colour, a little heavier ([HIDDEN_OUTLINE]),
 * and no fill; [LetterState.DIMMED], a faint hairline (`outlineVariant`), no fill, the letter quieter
 * (still 4.5:1 on the panel, `onSurfaceVariant`).
 */
@Composable
internal fun LetterState.look(): LetterLook {
    val colors = MaterialTheme.colorScheme
    val (marked, onMarked) = chipColours(marked = true)
    return when (this) {
        LetterState.SHOWN -> LetterLook(marked, null, onMarked)
        LetterState.UNVERIFIED -> {
            val (fill, ink) = unverifiedColours(colors.outline, colors.surface, colors.onSurface)
            LetterLook(fill, null, ink)
        }
        LetterState.HIDDEN ->
            LetterLook(Color.Transparent, BorderStroke(HIDDEN_OUTLINE, colors.onSurface), colors.onSurface)
        LetterState.DIMMED ->
            LetterLook(Color.Transparent, BorderStroke(HAIRLINE, colors.outlineVariant), colors.onSurfaceVariant)
    }
}

/**
 * The unverified chip's fill and its letter's colour (#951), from the scheme's [outline], [surface]
 * and [onSurface]: the letter white, or the darker of the surface and its text, whichever has the more
 * contrast with [outline]; the fill [outline], darkened (for a light letter) or lightened (for a dark
 * one) a step ([SHADE_STEP]) at a time until the letter is [MIN_CONTRAST] on it (WCAG): white on the
 * default light scheme's `#757780` is 4.46:1, so it fills `#6f717a`; the dark scheme's `#8f9099`
 * passes as it is. Should no step reach it, the letter is the better of white and black.
 */
internal fun unverifiedColours(
    outline: Color,
    surface: Color,
    onSurface: Color,
): Pair<Color, Color> {
    val dark = if (surface.luminance() < onSurface.luminance()) surface else onSurface
    val ink = if (contrast(Color.White, outline) >= contrast(dark, outline)) Color.White else dark
    val toward = if (ink.luminance() > outline.luminance()) Color.Black else Color.White
    var fill = outline
    var steps = 0
    while (contrast(ink, fill) < MIN_CONTRAST && steps < MAX_SHADE_STEPS) {
        fill = lerp(fill, toward, SHADE_STEP)
        steps++
    }
    return if (contrast(ink, fill) >= MIN_CONTRAST) {
        fill to ink
    } else {
        outline to if (contrast(Color.White, outline) >= contrast(Color.Black, outline)) Color.White else Color.Black
    }
}

/** The contrast ratio of two colours, as WCAG counts it. */
internal fun contrast(
    a: Color,
    b: Color,
): Float {
    val x = a.luminance()
    val y = b.luminance()
    return (max(x, y) + CONTRAST_OFFSET) / (min(x, y) + CONTRAST_OFFSET)
}

/**
 * The table's legend (#723, #951), under it while it has rows, outside the developer mark, its two
 * columns [width] and [gap] apart as the table's: closed each time it is composed (each time
 * *Sources* opens), one line, a small **?** pill right-aligned in the chips' column and *Explain the
 * source chips* in the names', the whole line one button (*collapsed*); a tap opens it in that
 * line's place: the heading *Source chips*, a button (*expanded*) that closes it again, then a row
 * for each look of the chips ([LEGEND_ROWS]), its sample ([LetterSample]) and its text, and *Tap a
 * source to show or hide its results.* in the names' column. Each button is read by its words
 * (*Explain the source chips, button, collapsed*), the pill not; the rows are read as text, their
 * samples hidden from a screen reader. Each tap hands the focus to the control that takes the tapped
 * one's place. [modifier] goes on its outside: the developer mark's inset, with the table in it.
 */
@Composable
internal fun Legend(
    width: Dp,
    gap: Dp,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val handoff = rememberFocusHandoff()
    val ask = remember { FocusRequester() }
    val heading = remember { FocusRequester() }
    val style = MaterialTheme.typography.bodySmall
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier.testTag(SOURCES_LEGEND_TAG)) {
        if (!open) {
            val explain = stringResource(R.string.home_sources_legend_explain)
            val collapsed = stringResource(R.string.home_fold_collapsed)
            Row(
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = LEGEND_TARGET)
                        .testTag(SOURCES_LEGEND_ASK_TAG)
                        .handoffTarget(handoff, ask)
                        .clickable(role = Role.Button) {
                            open = true
                            handoff.moveTo(heading)
                        }.semantics { stateDescription = collapsed },
            ) {
                Box(Modifier.width(width), contentAlignment = Alignment.CenterEnd) { Pill() }
                Text(explain, style = style, color = colour, modifier = Modifier.weight(1f))
            }
        } else {
            val title = stringResource(R.string.home_sources_legend_title)
            val expanded = stringResource(R.string.home_fold_expanded)
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = LEGEND_TARGET)
                        .testTag(SOURCES_LEGEND_HEADING_TAG)
                        .handoffTarget(handoff, heading)
                        .clickable(role = Role.Button) {
                            open = false
                            handoff.moveTo(ask)
                        }.semantics { stateDescription = expanded },
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            }
            LegendRows(width, gap)
        }
    }
}

/** The open legend's rows (#951), its samples in a column [width] wide, [gap] from their texts. */
@Composable
private fun LegendRows(
    width: Dp,
    gap: Dp,
) {
    val style = MaterialTheme.typography.bodySmall
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    val hidden = Modifier.semantics { hideFromAccessibility() }
    val density = LocalDensity.current
    // The samples scaled as the words beside them are, so in a large font they keep to them; each
    // centred on its text's first line.
    val scale = with(density) { style.fontSize.toDp() / style.fontSize.value.dp }
    val line = with(density) { style.lineHeight.toDp() }
    Column(verticalArrangement = Arrangement.spacedBy(LEGEND_GAP)) {
        for ((state, words) in LEGEND_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
                Box(Modifier.width(width).height(line).then(hidden), contentAlignment = Alignment.CenterEnd) {
                    LetterSample(state, scale, hidden)
                }
                Text(stringResource(words), style = style, color = colour, modifier = Modifier.weight(1f))
            }
        }
        Text(
            stringResource(R.string.home_sources_legend_tap),
            style = style,
            color = colour,
            modifier = Modifier.padding(start = width + gap, top = LEGEND_GAP),
        )
    }
}

/** The legend's rows, in order (#951): each look, and what it says. */
private val LEGEND_ROWS =
    listOf(
        LetterState.SHOWN to R.string.home_sources_legend_verified,
        LetterState.UNVERIFIED to R.string.home_sources_legend_unverified,
        LetterState.HIDDEN to R.string.home_sources_legend_hidden,
        LetterState.DIMMED to R.string.home_sources_legend_none,
    )

/**
 * The closed legend's small **?** pill (#951), a hairline round a question mark; not read by a screen
 * reader, which reads the words beside it as the button's name.
 */
@Composable
private fun Pill() {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .testTag(SOURCES_LEGEND_PILL_TAG)
                .clearAndSetSemantics {}
                .border(HAIRLINE, colors.outlineVariant, shape)
                .padding(horizontal = PILL_PADDING_H, vertical = PILL_PADDING_V),
    ) {
        Text(
            stringResource(R.string.home_sources_legend_pill),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
    }
}

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

// The legend's samples at the default font size; the space between its rows; the least height of
// its buttons, a touch area; the pill's padding.
private val SAMPLE_WIDTH = 22.dp
private val SAMPLE_HEIGHT = 14.dp
private val LEGEND_GAP = 6.dp
private val LEGEND_TARGET = 48.dp
private val PILL_PADDING_H = 9.dp
private val PILL_PADDING_V = 2.dp

// WCAG's offset in a contrast ratio; the least contrast of a letter on its chip, as WCAG asks of
// text; how far each step of the unverified fill moves toward black or white, and how many it may take.
private const val CONTRAST_OFFSET = 0.05f
private const val MIN_CONTRAST = 4.5f
private const val SHADE_STEP = 0.05f
private const val MAX_SHADE_STEPS = 40

/** The test tag of the table's legend (#723). */
const val SOURCES_LEGEND_TAG: String = "sources-legend"

/** The test tag of the closed legend's line, the button that opens it (#951). */
const val SOURCES_LEGEND_ASK_TAG: String = "sources-legend:ask"

/** The test tag of the closed legend's **?** pill (#951). */
const val SOURCES_LEGEND_PILL_TAG: String = "sources-legend:pill"

/** The test tag of the open legend's heading, the button that closes it (#951). */
const val SOURCES_LEGEND_HEADING_TAG: String = "sources-legend:heading"
