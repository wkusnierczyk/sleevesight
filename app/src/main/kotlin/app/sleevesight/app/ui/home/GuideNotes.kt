package app.sleevesight.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.LinkLook

// The guide's notes as buttons, each opening a panel about its part (#673), and the pen's marks.

/**
 * The note on [part] (#627), in the hand-drawn [style], marked as a link is ([LinkLook]), since a tap
 * on it opens its panel ([onClick]; a second tap closes it). A screen reader reads it as a button,
 * the part's name before the note ("Settings: tap to configure, button"). [modifier] goes on the
 * outside: a focus requester, so the focus can come back to it from the panel's *Dismiss*.
 */
@Composable
internal fun GuideNote(
    part: GuidePart,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val note = stringResource(part.note)
    val named = stringResource(R.string.home_guide_named_note, stringResource(part.label), note)
    Box(
        modifier
            .testTag(guideNoteTag(part))
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = named },
    ) {
        LinkLook(note, style)
    }
}

/**
 * The panel of [part]'s note (#673), laid over the picture's phone screen: the part's name as a
 * heading, its fuller description in the app's own type, and *Dismiss* ([onDismiss], with
 * [dismissModifier]). Opaque, so the drawn Home under it does not show through, and taps on it stay
 * in it. A screen reader reads the name and description as one region as it opens.
 */
@Composable
internal fun GuideNotePanel(
    part: GuidePart,
    modifier: Modifier = Modifier,
    dismissModifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = modifier.testTag(GUIDE_NOTE_PANEL_TAG),
        shape = RoundedCornerShape(PANEL_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 2.dp)) {
            Column(
                Modifier
                    .padding(end = 8.dp)
                    .testTag(GUIDE_NOTE_PANEL_TEXT_TAG)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(part.label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(part.about), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(
                onClick = onDismiss,
                modifier = dismissModifier.align(Alignment.End).testTag(GUIDE_NOTE_DISMISS_TAG),
            ) { Text(stringResource(R.string.home_guide_dismiss)) }
        }
    }
}

/**
 * The pen's marks on the guide, in [pen]: the [arrows] from the notes to their parts, and around the
 * part an open note is about ([outline], in the picture's measure), a rounded line (#673). Drawn
 * over the picture and the notes and under a note's panel.
 */
@Composable
internal fun GuideMarks(
    arrows: State<List<GuideArrow>>,
    outline: () -> Rect?,
    pen: Color,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier.drawBehind {
            val stroke = Stroke(width = PEN.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            for (arrow in arrows.value) drawArrow(arrow, pen, stroke)
            outline()?.let { part ->
                val around = part.inflate(OUTLINE_GAP.toPx())
                drawRoundRect(
                    pen,
                    topLeft = around.topLeft,
                    size = around.size,
                    cornerRadius = CornerRadius(OUTLINE_CORNER.toPx()),
                    style = Stroke(width = OUTLINE.toPx()),
                )
            }
        },
    )
}

/** The test tag of the note on [part]. */
internal fun guideNoteTag(part: GuidePart): String = "home-guide-note:${part.name.lowercase()}"

/** The test tags of a note's panel, of its name and description, and of its *Dismiss*. */
const val GUIDE_NOTE_PANEL_TAG: String = "home-guide-note-panel"
const val GUIDE_NOTE_PANEL_TEXT_TAG: String = "home-guide-note-panel-text"
const val GUIDE_NOTE_DISMISS_TAG: String = "home-guide-note-dismiss"

private val PANEL_CORNER = 12.dp
private val PEN = 1.5.dp
private val OUTLINE = 2.dp
private val OUTLINE_GAP = 3.dp
private val OUTLINE_CORNER = 6.dp
