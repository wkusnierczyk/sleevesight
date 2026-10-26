package app.sleevesight.app.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sleevesight.app.R

// The developer mark (#536): what only developer mode shows, outlined in a colour of its own with a
// small *Developer* tag on the outline's top edge, so a tester's screen and screenshots show what
// other users will not see. One look, drawn here only: each place that shows something only in
// developer mode wraps it in [DeveloperMark].

/**
 * Whether what only developer mode shows is marked (#536), as [SleevesightTheme] was given it:
 * developer mode on, with *Mark developer items* (`Settings.marksDeveloperItems`). Off outside it.
 */
internal val LocalDeveloperMark = staticCompositionLocalOf { false }

/**
 * The mark's colour, the `DeveloperMark` token: teal, darker on a light theme and lighter on a dark
 * one ([isAppInDarkTheme]). Fixed, not the theme's: the phone's dynamic colours and the album art's
 * follow outside input and could take on any hue, the mark's among them; and blue is the code
 * font's and often the phone's accent.
 */
@Composable
internal fun developerMarkColor(): Color = Color(if (isAppInDarkTheme()) DEVELOPER_MARK_DARK else DEVELOPER_MARK_LIGHT)

/**
 * The *Developer* tag's text laid out (10 sp, the type scale's smallest label, in the mark's
 * colour), and the room the mark adds above and below the content, which follow the font size.
 */
private class TagLayout(
    val label: TextLayoutResult,
    val box: Size,
    val room: Dp,
    val minWidth: Dp,
)

@Composable
private fun rememberTagLayout(ink: Color): TagLayout {
    val measurer = rememberTextMeasurer()
    val tag = stringResource(R.string.developer_mark_tag)
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelSmall.copy(color = ink, fontSize = TAG_TEXT)
    return remember(tag, style, density) {
        val label = measurer.measure(tag, style)
        with(density) {
            val box =
                Size(
                    label.size.width + 2 * TAG_PADDING_H.toPx(),
                    label.size.height + 2 * TAG_PADDING_V.toPx(),
                )
            // Half the tag above the outline, in room kept for it; the outline wide enough for it
            // with the corners clear.
            TagLayout(label, box, (box.height / 2).toDp(), box.width.toDp() + CORNER * 2)
        }
    }
}

/**
 * How much taller the mark makes what it wraps, at the current font size: the room for the tag
 * above the outline, the content's start below the tag, and the room at the bottom.
 */
@Composable
internal fun developerMarkHeight(): Dp {
    val tag = rememberTagLayout(developerMarkColor())
    return tag.room + tag.room + TAG_CLEARANCE + PADDING_V
}

/**
 * [content], shown only in developer mode, in a column (its children [spacing] apart), marked as
 * such while [LocalDeveloperMark] is on: an outline ([OUTLINE] wide, rounded) in
 * [developerMarkColor], with a little room inside, and across its top edge near the end a small
 * *Developer* tag, in the mark's colour on the surface's, with a thin border of the mark's colour.
 * The content starts below the tag; the outline is at least as wide as the tag, with the content
 * centred in it when narrower (the report button). The tag is drawn, not composed: a screen reader
 * reads the content as it would unmarked. Off, the column alone, with no room kept for the mark.
 * Marked or not, it is the same column, only its modifier changing, so turning the mark on or off
 * keeps the content's state and focus.
 */
@Composable
internal fun DeveloperMark(
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val marked = LocalDeveloperMark.current
    val ink = developerMarkColor()
    val paper = MaterialTheme.colorScheme.surface
    val tag = rememberTagLayout(ink)
    val mark =
        if (marked) {
            Modifier
                .testTag(DEVELOPER_MARK_TAG)
                .padding(top = tag.room)
                .drawWithContent {
                    drawContent()
                    drawMark(ink, paper, tag)
                }.centredIn(tag.minWidth)
                .padding(start = PADDING_H, end = PADDING_H, top = tag.room + TAG_CLEARANCE, bottom = PADDING_V)
        } else {
            Modifier
        }
    Column(modifier.then(mark), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

// The outline around the whole of the space drawn in, and the tag across its top edge near the end
// (centred when there is no room to set it in from the end).
private fun ContentDrawScope.drawMark(
    ink: Color,
    paper: Color,
    tag: TagLayout,
) {
    val stroke = OUTLINE.toPx()
    drawRoundRect(
        ink,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(CORNER.toPx()),
        style = Stroke(stroke),
    )
    val box = tag.box
    val inset = TAG_INSET.toPx()
    val left =
        when {
            box.width + 2 * inset > size.width -> (size.width - box.width) / 2
            layoutDirection == LayoutDirection.Rtl -> inset
            else -> size.width - inset - box.width
        }
    val top = -box.height / 2
    val corner = CornerRadius(TAG_CORNER.toPx())
    drawRoundRect(paper, Offset(left, top), box, corner)
    drawRoundRect(ink, Offset(left, top), box, corner, style = Stroke(TAG_BORDER.toPx()))
    drawText(tag.label, topLeft = Offset(left + TAG_PADDING_H.toPx(), top + TAG_PADDING_V.toPx()))
}

// At least [minWidth] wide (as far as the constraints allow), with what is narrower centred in it.
private fun Modifier.centredIn(minWidth: Dp): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0))
        val width =
            maxOf(placeable.width, minWidth.roundToPx(), constraints.minWidth).coerceAtMost(constraints.maxWidth)
        layout(width, placeable.height) { placeable.place((width - placeable.width) / 2, 0) }
    }

/** The test tag of a marked column (only while marked). */
const val DEVELOPER_MARK_TAG: String = "developer_mark"

/**
 * The mark's teal on a light theme: 5.1:1 on the light surface (tone 98, dynamic or not), 4.1:1 on
 * the darkest container (tone 90), above the 3:1 an outline needs and the 4.5:1 of the tag's text,
 * which is drawn on the surface.
 */
private const val DEVELOPER_MARK_LIGHT = 0xFF00796B

/**
 * The mark's teal on a dark theme: 9.8:1 on the dark surface (tone 6, dynamic or not), 6.5:1 on the
 * lightest container (tone 22).
 */
private const val DEVELOPER_MARK_DARK = 0xFF4DD0C0

private val OUTLINE = 1.5.dp
private val CORNER = 10.dp
private val PADDING_H = 8.dp
private val PADDING_V = 6.dp
private val TAG_TEXT = 10.sp
private val TAG_PADDING_H = 5.dp
private val TAG_PADDING_V = 2.dp
private val TAG_CORNER = 4.dp
private val TAG_BORDER = 1.dp
private val TAG_INSET = 10.dp
private val TAG_CLEARANCE = 2.dp
