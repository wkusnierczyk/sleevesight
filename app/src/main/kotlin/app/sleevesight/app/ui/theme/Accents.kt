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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.sp

// Links and switches without the palette's accent colour (#442): the system's dynamic accent (on
// Android 12 and later, from the wallpaper or the chosen colour theme) looked out of place, and
// links were styled two ways. Every link in the app is drawn here, by [LinkedText] or [LinkLabel],
// so none can take another look or lose its line (`PlainAccentsTest` holds the rest of the app to
// that).

/** A piece of a [LinkedText]: [Words], a [Link] to a web page, or an [Action] run on a tap. */
internal sealed interface TextPart {
    val text: String

    /** Plain text. */
    data class Words(
        override val text: String,
    ) : TextPart

    /** A link that opens [url] in the browser. */
    data class Link(
        override val text: String,
        val url: String,
    ) : TextPart

    /** A link that runs [onClick] in the app; [tag] names it to tests and to accessibility. */
    class Action(
        override val text: String,
        val tag: String,
        val onClick: () -> Unit,
    ) : TextPart
}

/**
 * [parts] as one text, each link in it marked as every link in the app is (#442): in the text
 * colour ([color], else the style's, else the content colour), with a thin line a little below the
 * text, clear of the descenders, which Android's own underline crosses; focused, hovered or
 * pressed, a faint tint of the text colour behind it.
 */
@Composable
internal fun LinkedText(
    parts: List<TextPart>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val styles = linkStyles(ink)
    val links = mutableListOf<IntRange>()
    val text =
        buildAnnotatedString {
            for (part in parts) {
                val start = length
                when (part) {
                    is TextPart.Words -> append(part.text)
                    is TextPart.Link -> withLink(LinkAnnotation.Url(part.url, styles)) { append(part.text) }
                    is TextPart.Action ->
                        withLink(LinkAnnotation.Clickable(part.tag, styles) { part.onClick() }) { append(part.text) }
                }
                if (part !is TextPart.Words) links += start until length
            }
        }
    UnderlinedText(text, links, style, ink, color, modifier)
}

/**
 * [text] as a link of its own, marked as [LinkedText]'s are, which the caller makes clickable: a
 * link with a full touch target, its focus shown by the clickable's own indication.
 */
@Composable
internal fun LinkLabel(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    UnderlinedText(AnnotatedString(text), listOf(text.indices), style, ink, color, modifier)
}

/** The one style of the app's link annotations: the text's own look, tinted while in use. */
private fun linkStyles(ink: Color): TextLinkStyles {
    val inUse = SpanStyle(background = ink.copy(alpha = IN_USE_ALPHA))
    return TextLinkStyles(style = null, focusedStyle = inUse, hoveredStyle = inUse, pressedStyle = inUse)
}

/** [text] with a line under each of [links], worked out once per layout and only drawn after. */
@Suppress("LongParameterList") // The text and its links, and how both are drawn.
@Composable
private fun UnderlinedText(
    text: AnnotatedString,
    links: List<IntRange>,
    style: TextStyle,
    ink: Color,
    color: Color,
    modifier: Modifier,
) {
    var lines by remember { mutableStateOf(emptyList<Rect>()) }
    Text(
        text,
        style = style,
        color = color,
        onTextLayout = { lines = underlines(it, links) },
        modifier = modifier.drawBehind { for (line in lines) drawRect(ink, line.topLeft, line.size) },
    )
}

/**
 * The lines under [links] in [layout]: on each line of text a link spans, one under each stretch
 * of it as drawn, so a link that mixes left-to-right and right-to-left text is underlined where its
 * glyphs are and nowhere between them.
 */
private fun underlines(
    layout: TextLayoutResult,
    links: List<IntRange>,
): List<Rect> {
    val em = emOf(layout)
    val thickness = maxOf(1f, em * UNDERLINE_THICKNESS)
    val lines = mutableListOf<Rect>()
    for (link in links.filterNot { it.isEmpty() }) {
        for (line in layout.getLineForOffset(link.first)..layout.getLineForOffset(link.last)) {
            val from = maxOf(link.first, layout.getLineStart(line))
            val to = minOf(link.last, layout.getLineEnd(line, visibleEnd = true) - 1)
            if (to < from) continue
            // Inside the line's own box, should a tight line height leave less room below.
            val top = minOf(layout.getLineBaseline(line) + em * UNDERLINE_GAP, layout.getLineBottom(line) - thickness)
            for ((left, right) in stretches(layout, from..to)) lines += Rect(left, top, right, top + thickness)
        }
    }
    return lines
}

/** The stretches, left to right, that the characters of [range] (on one line) are drawn over. */
private fun stretches(
    layout: TextLayoutResult,
    range: IntRange,
): List<Pair<Float, Float>> {
    val boxes = range.map { layout.getBoundingBox(it) }.filter { it.width > 0f }.sortedBy { it.left }
    val stretches = mutableListOf<Pair<Float, Float>>()
    for (box in boxes) {
        val last = stretches.lastOrNull()
        if (last != null && box.left <= last.second + TOUCHING) {
            stretches[stretches.lastIndex] = last.first to maxOf(last.second, box.right)
        } else {
            stretches += box.left to box.right
        }
    }
    return stretches
}

/** The text's em in pixels: its font size, which a style may leave out or give in ems. */
private fun emOf(layout: TextLayoutResult): Float =
    with(layout.layoutInput.density) {
        val size = layout.layoutInput.style.fontSize
        when {
            size.isSp -> size.toPx()
            size.isEm -> size.value * DEFAULT_FONT_SIZE.toPx()
            else -> DEFAULT_FONT_SIZE.toPx()
        }
    }

// The line's thickness and its distance below the baseline, in ems: about as thick as Roboto's
// own underline, and far enough down to leave a gap under g, p and y.
private const val UNDERLINE_THICKNESS = 0.05f
private const val UNDERLINE_GAP = 0.24f

// Compose's own default size, which an em is relative to when nothing sets one.
private val DEFAULT_FONT_SIZE = 14.sp

// Characters of one stretch touch, give or take rounding, in pixels.
private const val TOUCHING = 0.5f

private const val IN_USE_ALPHA = 0.12f

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
