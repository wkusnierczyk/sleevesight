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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
 * [content], shown only in developer mode, in a column (its children [spacing] apart), marked as
 * such while [LocalDeveloperMark] is on: an outline ([OUTLINE] wide, rounded) in
 * [developerMarkColor], with a little room inside, and across its top edge near the end a small
 * *Developer* tag, in the mark's colour on the surface's, with a thin border of the mark's colour.
 * The tag is drawn, not composed: a screen reader reads the content as it would unmarked. Off, the
 * column alone, with no room kept for the mark.
 */
@Composable
internal fun DeveloperMark(
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val arrangement = Arrangement.spacedBy(spacing)
    if (!LocalDeveloperMark.current) {
        Column(modifier, verticalArrangement = arrangement, content = content)
        return
    }
    val ink = developerMarkColor()
    val paper = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val tag = stringResource(R.string.developer_mark_tag)
    val density = LocalDensity.current
    // The tag's text: the type scale's smallest label, at 10 sp, in the mark's colour.
    val style = MaterialTheme.typography.labelSmall.copy(color = ink, fontSize = TAG_TEXT)
    val label = remember(tag, style, density) { measurer.measure(tag, style) }
    // The tag sits across the outline's top edge: half of it above, in room kept for it, and half
    // below, where the content starts only under it, so the tag never covers it.
    val room = with(density) { (label.size.height / 2f).toDp() + TAG_PADDING_V }
    Column(
        modifier
            .testTag(DEVELOPER_MARK_TAG)
            .padding(top = room)
            .drawWithContent {
                drawContent()
                val stroke = OUTLINE.toPx()
                drawRoundRect(
                    ink,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(CORNER.toPx()),
                    style = Stroke(stroke),
                )
                val padH = TAG_PADDING_H.toPx()
                val padV = TAG_PADDING_V.toPx()
                val box = Size(label.size.width + 2 * padH, label.size.height + 2 * padV)
                val inset = TAG_INSET.toPx()
                val left =
                    when {
                        // Too narrow to set it in from the end (the report button): centred.
                        box.width + 2 * inset > size.width -> (size.width - box.width) / 2
                        layoutDirection == LayoutDirection.Rtl -> inset
                        else -> size.width - inset - box.width
                    }
                val top = -box.height / 2
                val corner = CornerRadius(TAG_CORNER.toPx())
                drawRoundRect(paper, Offset(left, top), box, corner)
                drawRoundRect(ink, Offset(left, top), box, corner, style = Stroke(TAG_BORDER.toPx()))
                drawText(label, topLeft = Offset(left + padH, top + padV))
            }.padding(start = PADDING_H, end = PADDING_H, top = room + TAG_CLEARANCE, bottom = PADDING_V),
        verticalArrangement = arrangement,
        content = content,
    )
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
