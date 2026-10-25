package app.sleevesight.app.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle

// Links and switches without the palette's accent colour (#442): the system's dynamic accent (on
// Android 12 and later, from the wallpaper or the chosen colour theme) looked out of place, and
// links were styled two ways.

/**
 * The one style of every link annotation in the app (#442): the link keeps the colour of the text
 * around it, and [LinkedText] draws its line; pressed, it takes a faint tint of the text colour.
 */
@Composable
internal fun linkStyles(): TextLinkStyles =
    TextLinkStyles(
        style = null,
        pressedStyle = SpanStyle(background = LocalContentColor.current.copy(alpha = PRESSED_ALPHA)),
    )

/**
 * [text] with each of its links ([linkStyles]) marked as every link in the app is (#442): in the
 * text colour ([color], else the style's, else the content colour), with a thin line a little below
 * the text, clear of the descenders, which Android's own underline crosses. With [whole], all of
 * [text] is one link, which the caller makes clickable (a link with a full touch target).
 */
@Composable
internal fun LinkedText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    whole: Boolean = false,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    Text(
        text,
        style = style,
        color = color,
        onTextLayout = { layout = it },
        modifier =
            modifier.drawBehind {
                val drawn = layout ?: return@drawBehind
                val links =
                    if (whole) {
                        listOf(text.indices)
                    } else {
                        text.getLinkAnnotations(0, text.length).map { it.start until it.end }
                    }
                for (link in links) underline(drawn, link, ink)
            },
    )
}

/** The line under [range] of [layout], on each line of text it spans. */
private fun DrawScope.underline(
    layout: TextLayoutResult,
    range: IntRange,
    ink: Color,
) {
    if (range.isEmpty()) return
    val em =
        layout.layoutInput.style.fontSize
            .toPx()
    val thickness = maxOf(1f, em * UNDERLINE_THICKNESS)
    for (line in layout.getLineForOffset(range.first)..layout.getLineForOffset(range.last)) {
        val from = maxOf(range.first, layout.getLineStart(line))
        val to = minOf(range.last, layout.getLineEnd(line, visibleEnd = true) - 1)
        if (to < from) continue
        // Either end may be the left one, in right-to-left text.
        val first = layout.getBoundingBox(from)
        val last = layout.getBoundingBox(to)
        val left = minOf(first.left, last.left)
        val right = maxOf(first.right, last.right)
        drawRect(ink, Offset(left, layout.getLineBaseline(line) + em * UNDERLINE_GAP), Size(right - left, thickness))
    }
}

// The line's thickness and its distance below the baseline, in ems: about as thick as Roboto's
// own underline, and far enough down to leave a gap under g, p and y.
private const val UNDERLINE_THICKNESS = 0.05f
private const val UNDERLINE_GAP = 0.24f

private const val PRESSED_ALPHA = 0.12f

/**
 * A switch's colours (#442): on, the track in the text colour and the thumb in the surface's (black
 * and white, white and black in dark mode); off, and disabled, Material's own greys.
 */
@Composable
internal fun plainSwitchColors(): SwitchColors {
    val colors = MaterialTheme.colorScheme
    return SwitchDefaults.colors(
        checkedThumbColor = colors.surface,
        checkedTrackColor = colors.onSurface,
        checkedBorderColor = colors.onSurface,
        checkedIconColor = colors.onSurface,
    )
}
