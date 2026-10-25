package app.sleevesight.app.ui.theme

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.sp
import app.sleevesight.core.settings.LinkStyle

// Links without the palette's accent colour (#442): the system's dynamic accent (on
// Android 12 and later, from the wallpaper or the chosen colour theme) looked out of place, and
// links were styled two ways. Every link in the app is drawn here, by [LinkedText] or [LinkLabel],
// so none can take another look or lose its line (`LinkSourcesTest` holds the rest of the app to
// that). How a link is marked, a line below it or a highlight behind it, is *Link style* (#519),
// which [SleevesightTheme] provides as [LocalLinkStyle].

/**
 * *Link style* (#519), as [SleevesightTheme] was given it: how every link drawn here is marked, a
 * thin line below it ([LinkStyle.UNDERLINE], the default) or a faint highlight behind it
 * ([LinkStyle.HIGHLIGHT]).
 */
internal val LocalLinkStyle = staticCompositionLocalOf { LinkStyle.UNDERLINE }

/**
 * [parts] as one text, each link in it marked as every link in the app is (#442): in the text
 * colour ([color], else the style's, else the content colour), with a thin line a little below the
 * text, clear of the descenders, which Android's own underline crosses, or, as *Link style* says
 * (#519, [LocalLinkStyle]), a faint rounded highlight of the text colour behind it; focused,
 * hovered or pressed, a faint tint of the text colour behind it.
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
                    is TextPart.Action -> withLink(annotation(part, styles, part.onClick)) { append(part.text) }
                }
                if (part is TextPart.Linked) links += start until length
            }
        }
    MarkedText(text, links, style, ink, color, modifier)
}

/**
 * [link] as a text of its own, all of it the link, marked as [LinkedText]'s are: one link
 * annotation over the whole text, so a screen reader finds it among the links and keyboard focus
 * stops on it once, and a tap anywhere in its touch target, at least the minimum (48 dp), follows
 * it. A screen reader names what a tap does with [onClickLabel].
 */
@Composable
internal fun LinkLabel(
    link: TextPart.Linked,
    onClickLabel: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val follow by rememberUpdatedState(rememberFollow(link))
    val text = buildAnnotatedString { withLink(annotation(link, linkStyles(ink)) { follow() }) { append(link.text) } }
    // The text's node says what a tap does, and with the touch target around the text, outside
    // it, takes taps; it takes no focus, so the link stays the one stop. A tap on the text itself
    // is the link's.
    val target =
        modifier
            .semantics {
                onClick(label = onClickLabel) {
                    follow()
                    true
                }
            }.pointerInput(Unit) { detectTapGestures { follow() } }
            .minimumInteractiveComponentSize()
    MarkedText(text, listOf(link.text.indices), style, ink, color, target)
}

/**
 * [text] drawn as a link is, inside a control that is itself what a tap and a screen reader act on
 * (a settings row that opens a page): the look without a link of its own, which would be a second
 * target inside the first.
 */
@Composable
internal fun LinkLook(
    text: String,
    style: TextStyle,
    color: Color = Color.Unspecified,
) {
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    MarkedText(AnnotatedString(text), listOf(text.indices), style, ink, color, Modifier)
}

/** What following [link] does: open its page in the browser, or run its action. */
@Composable
private fun rememberFollow(link: TextPart.Linked): () -> Unit {
    val uriHandler = LocalUriHandler.current
    return when (link) {
        is TextPart.Link ->
            remember(uriHandler, link.url) {
                {
                    try {
                        uriHandler.openUri(link.url)
                    } catch (_: IllegalArgumentException) {
                        // No app opens web pages: the address is on the screen.
                    }
                }
            }
        is TextPart.Action -> link.onClick
    }
}

/** [link]'s annotation, in [styles], running [follow] when it is followed. */
private fun annotation(
    link: TextPart.Linked,
    styles: TextLinkStyles,
    follow: () -> Unit,
): LinkAnnotation =
    when (link) {
        is TextPart.Link -> LinkAnnotation.Url(link.url, styles) { follow() }
        is TextPart.Action -> LinkAnnotation.Clickable(link.tag, styles) { follow() }
    }

/** The one style of the app's link annotations: the text's own look, tinted while in use. */
private fun linkStyles(ink: Color): TextLinkStyles {
    val inUse = SpanStyle(background = ink.copy(alpha = IN_USE_ALPHA))
    return TextLinkStyles(style = null, focusedStyle = inUse, hoveredStyle = inUse, pressedStyle = inUse)
}

/**
 * [text] with each of [links] marked as *Link style* says ([LocalLinkStyle]): a line under it, or a
 * highlight behind it. The marks are worked out once per layout and style, and only drawn after.
 */
@Suppress("LongParameterList") // The text and its links, and how both are drawn.
@Composable
private fun MarkedText(
    text: AnnotatedString,
    links: List<IntRange>,
    style: TextStyle,
    ink: Color,
    color: Color,
    modifier: Modifier,
) {
    val look = LocalLinkStyle.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val marks = remember(layout, links, look) { layout?.let { marks(it, links, look) } ?: Marks.NONE }
    Text(
        text,
        style = style,
        color = color,
        onTextLayout = { layout = it },
        modifier =
            modifier.drawBehind {
                when (look) {
                    LinkStyle.UNDERLINE -> for (mark in marks.rects) drawRect(ink, mark.topLeft, mark.size)
                    LinkStyle.HIGHLIGHT -> {
                        val fill = ink.copy(alpha = ink.alpha * HIGHLIGHT_ALPHA)
                        for (mark in marks.rects) drawRoundRect(fill, mark.topLeft, mark.size, marks.corner)
                    }
                }
            },
    )
}

/** The rectangles that mark a text's links, and the corners of a highlight's. */
private class Marks(
    val rects: List<Rect>,
    val corner: CornerRadius,
) {
    companion object {
        val NONE = Marks(emptyList(), CornerRadius.Zero)
    }
}

/**
 * The marks of [links] in [layout], as [look] draws them: on each line of text a link spans, one
 * under (or behind) each stretch of it as drawn, so a link that mixes left-to-right and
 * right-to-left text is marked where its glyphs are and nowhere between them.
 */
private fun marks(
    layout: TextLayoutResult,
    links: List<IntRange>,
    look: LinkStyle,
): Marks {
    val em = emOf(layout)
    val thickness = maxOf(1f, em * UNDERLINE_THICKNESS)
    val rects = mutableListOf<Rect>()
    for (link in links.filterNot { it.isEmpty() }) {
        for (line in layout.getLineForOffset(link.first)..layout.getLineForOffset(link.last)) {
            val from = maxOf(link.first, layout.getLineStart(line))
            val to = minOf(link.last, layout.getLineEnd(line, visibleEnd = true) - 1)
            if (to < from) continue
            val baseline = layout.getLineBaseline(line)
            for ((left, right) in stretches(layout, from..to)) {
                rects +=
                    when (look) {
                        // Inside the line's own box, should a tight line height leave less room below.
                        LinkStyle.UNDERLINE -> {
                            val top = minOf(baseline + em * UNDERLINE_GAP, layout.getLineBottom(line) - thickness)
                            Rect(left, top, right, top + thickness)
                        }
                        // From above the capitals to below the descenders, a little wider than the
                        // glyphs, and inside the line's box, so lines close together keep apart.
                        LinkStyle.HIGHLIGHT -> {
                            val pad = em * HIGHLIGHT_PADDING
                            Rect(
                                left - pad,
                                maxOf(baseline - em * HIGHLIGHT_ABOVE, layout.getLineTop(line)),
                                right + pad,
                                minOf(baseline + em * HIGHLIGHT_BELOW, layout.getLineBottom(line)),
                            )
                        }
                    }
            }
        }
    }
    val corner = if (look == LinkStyle.HIGHLIGHT) CornerRadius(em * HIGHLIGHT_CORNER) else CornerRadius.Zero
    return Marks(rects, corner)
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

// The highlight (#519), in ems: from the baseline up past the capitals and down past the
// descenders, a little wider than the glyphs, with rounded corners, filled with the text colour at
// a faint alpha, as #442 rendered it beside the underline (Roboto, 16 sp: 20 dp tall over 13 dp of
// glyphs, 3 dp each side, 4 dp corners, 10 % ink).
private const val HIGHLIGHT_ABOVE = 0.86f
private const val HIGHLIGHT_BELOW = 0.38f
private const val HIGHLIGHT_PADDING = 0.17f
private const val HIGHLIGHT_CORNER = 0.24f
private const val HIGHLIGHT_ALPHA = 0.10f

// Compose's own default size, which an em is relative to when nothing sets one.
private val DEFAULT_FONT_SIZE = 14.sp

// Characters of one stretch touch, give or take rounding, in pixels.
private const val TOUCHING = 0.5f

private const val IN_USE_ALPHA = 0.12f
