package app.sleevesight.app.ui.theme

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import app.sleevesight.app.ui.openWebLink
import app.sleevesight.app.ui.rememberOpenUrl
import app.sleevesight.core.settings.LinkStyle
import kotlin.math.roundToInt

// Links without the palette's accent colour (#442): the system's dynamic accent (on
// Android 12 and later, from the wallpaper or the chosen colour theme) looked out of place, and
// links were styled two ways. Every link in the app is drawn here, by [LinkedText], [NamedLinkText]
// or [LinkLabel], so none can take another look or lose its line (`LinkSourcesTest` holds the rest of
// the app to that). How a link is marked, a line below it or a highlight behind it, is *Link style* (#519),
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
    marks: List<AnnotatedString.Range<SpanStyle>> = emptyList(),
) {
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val (text, links) = linkedString(parts, ink, marks)
    MarkedText(text, links, style, ink, color, modifier)
}

/**
 * [parts] as one text, as [LinkedText] has it, and where its links are; [marks] over the whole text.
 */
@Composable
private fun linkedString(
    parts: List<TextPart>,
    ink: Color,
    marks: List<AnnotatedString.Range<SpanStyle>> = emptyList(),
): Pair<AnnotatedString, List<IntRange>> {
    val styles = linkStyles(ink)
    // A link opens only through [openWebLink] (#1040), never by Compose's own handling of the address.
    val uris = LocalUriHandler.current
    val links = mutableListOf<IntRange>()
    val text =
        buildAnnotatedString {
            for (part in parts) {
                val start = length
                when (part) {
                    is TextPart.Words -> append(part.text)
                    is TextPart.Styled -> withStyle(part.style) { append(part.text) }
                    is TextPart.Link ->
                        withLink(LinkAnnotation.Url(part.url, styles) { openWebLink(uris, part.url) }) {
                            append(part.text)
                        }
                    is TextPart.Action -> withLink(annotation(part, styles, part.onClick)) { append(part.text) }
                }
                if (part is TextPart.Linked) links += start until length
            }
            // Over the whole text, the links' parts too (a search's marks, #847).
            for (mark in marks) addStyle(mark.item, mark.start, mark.end)
        }
    return text to links
}

/**
 * [parts], a text with one link in it, a [TextPart.Action], drawn as [LinkedText] draws it, the link
 * named for a screen reader with what a tap does ([onClickLabel], #956), which a link inside a text
 * cannot be given: a screen reader reads the text whole ([tag] on it), with no link in it, then stops
 * on a node of the link's own over its words, a link named so. A tap on the words follows the text's
 * link, as in [LinkedText].
 *
 * The two are one traversal group, the text first and the link second, whatever their places: a
 * link wrapped over two lines is a box as tall as the text and further left than its right end,
 * which the screen reader's order by place would put first. The link's node fills the group and is
 * placed by the text's own coordinates, which are physical, left to right, so in a right-to-left
 * layout too it lies over its words.
 */
@Suppress("LongParameterList") // The text, its link's name, and how it is drawn.
@Composable
internal fun NamedLinkText(
    parts: List<TextPart>,
    onClickLabel: String,
    style: TextStyle,
    tag: String,
    color: Color = Color.Unspecified,
) {
    val link = parts.filterIsInstance<TextPart.Action>().single()
    val ink = color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } }
    val (text, links) = linkedString(parts, ink)
    val start = parts.takeWhile { it !== link }.sumOf { it.text.length }
    val end = start + link.text.length
    // The text's layout, read only to measure and place the link's node, never in composition: a
    // new layout composing again would lay the text out again, without end.
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val named = buildAnnotatedString { withLink(annotation(link, linkStyles(ink), link.onClick)) { append(link.text) } }
    Box(Modifier.semantics { isTraversalGroup = true }) {
        val words =
            Modifier.clearAndSetSemantics {
                this.text = AnnotatedString(text.text)
                testTag = tag
                traversalIndex = 0f
            }
        MarkedText(text, links, style, ink, color, words) { layout.value = it }
        Box(
            Modifier
                // As large as the text, at its top left in either direction, so the box placed in it
                // is where the text measured it.
                .matchParentSize()
                .layout { measurable, constraints ->
                    // Each letter's box, joined: over two lines, the box around both parts.
                    val bounds =
                        layout.value
                            ?.takeIf { end <= it.layoutInput.text.length }
                            ?.let { laid -> (start until end).map(laid::getBoundingBox).reduce(::around) }
                            ?: Rect.Zero
                    val width = bounds.width.roundToInt()
                    val height = bounds.height.roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(width, height))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        // Not mirrored: the text's coordinates are from its left whatever the direction.
                        placeable.place(bounds.left.roundToInt(), bounds.top.roundToInt())
                    }
                }.semantics {
                    this.text = named
                    testTag = link.tag
                    traversalIndex = 1f
                    onClick(label = onClickLabel) {
                        link.onClick()
                        true
                    }
                },
        )
    }
}

// The box around [a] and [b].
private fun around(
    a: Rect,
    b: Rect,
): Rect = Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

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

/** What following [link] does: open its page in the browser ([openWebLink], #1040), or run its action. */
@Composable
private fun rememberFollow(link: TextPart.Linked): () -> Unit =
    when (link) {
        is TextPart.Link -> rememberOpenUrl(link.url)
        is TextPart.Action -> link.onClick
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
 * highlight behind it. The marks are worked out in the draw phase, from the layout the same frame
 * made, so a link is marked on its first frame and at once after a new layout. They are worked out
 * again only when the layout, the links, the style or the colour change; other draws only draw them.
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
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val look = LocalLinkStyle.current
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        style = style,
        color = color,
        onTextLayout = {
            layout.value = it
            onTextLayout(it)
        },
        modifier =
            modifier.drawWithCache {
                // Read here, in the draw phase, the layout is the one this frame measured.
                val marks = layout.value?.let { marks(it, links, look) } ?: Marks.NONE
                val fill = ink.copy(alpha = ink.alpha * HIGHLIGHT_ALPHA)
                onDrawBehind {
                    when (look) {
                        LinkStyle.UNDERLINE -> for (mark in marks.rects) drawRect(ink, mark.topLeft, mark.size)
                        LinkStyle.HIGHLIGHT ->
                            for (mark in marks.rects) drawRoundRect(fill, mark.topLeft, mark.size, marks.corner)
                    }
                }
            },
    )
}

// The highlight's fill (#519): the text colour at this alpha.
private const val HIGHLIGHT_ALPHA = 0.10f

private const val IN_USE_ALPHA = 0.12f
