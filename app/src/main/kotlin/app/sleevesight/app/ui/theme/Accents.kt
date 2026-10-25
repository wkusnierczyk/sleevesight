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

// The highlight's fill (#519): the text colour at this alpha.
private const val HIGHLIGHT_ALPHA = 0.10f

private const val IN_USE_ALPHA = 0.12f
