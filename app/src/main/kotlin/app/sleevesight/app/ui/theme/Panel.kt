package app.sleevesight.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import kotlin.math.roundToInt

// A panel that opens in a chip's place (#558, #623, #649): the frame a genre's panel and *Sources*'
// share on Home, and each section of the settings.

/**
 * A panel (frame B of the genre panel's mock-ups, #558; *Sources*' on Home since #623, and each
 * section of the settings since #649): on a light fill, rounded, [title] as a heading, with *Close*
 * at the top right when there is [onClose] (with [closeModifier], such as a focus requester); under
 * a rule, [content], its items [spacing] apart. [headingFrame] wraps the heading's row, the title
 * and *Close*: in the settings, where a link to the section lands. [modifier] goes on the outside.
 * Tagged [tag] for tests, its heading `<tag>:heading` and its *Close* `<tag>:close`.
 *
 * With [subtitle] (the name of the song, the artist or the album, under its kind, #894), it is drawn
 * under the title, quieter, whole on as many lines as it needs, and read with the title as one
 * heading; *Close*, and before it [actions] (an item's *Read aloud*, #894), stay centred on the
 * title's line, not on the two: [actions] is handed the modifier that centres it there, for the
 * outside of one layout of its own (the row reads it from its direct child). Without a
 * subtitle, the heading is as it always was, and [actions] is not drawn.
 */
@Suppress("LongParameterList") // What the panel shows, and where its users hook into it.
@Composable
internal fun Panel(
    title: AnnotatedString,
    tag: String,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    closeModifier: Modifier = Modifier,
    spacing: Dp = 10.dp,
    subtitle: String? = null,
    actions: @Composable (centred: Modifier) -> Unit = {},
    headingFrame: @Composable (@Composable () -> Unit) -> Unit = { it() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val ground = MaterialTheme.colorScheme.onSurface.copy(alpha = PANEL_FILL)
    Column(
        modifier =
            modifier
                .testTag(tag)
                .fillMaxWidth()
                .clip(shape)
                .background(ground, shape)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        headingFrame {
            if (subtitle == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(vertical = 10.dp)
                                .testTag("$tag:heading")
                                .semantics { heading() },
                    )
                    onClose?.let { PanelClose(tag, closeModifier, it) }
                }
            } else {
                SubtitledHeading(title, subtitle, tag, closeModifier, onClose, actions)
            }
        }
        Column(modifier = Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(spacing)) {
            PanelRule()
            content()
        }
    }
}

// A heading of [title] with [subtitle] under it, read as one heading, [actions] and *Close* each
// centred on the title's line ([Panel]).
@Suppress("LongParameterList") // What the heading shows, and its controls.
@Composable
private fun SubtitledHeading(
    title: AnnotatedString,
    subtitle: String,
    tag: String,
    closeModifier: Modifier,
    onClose: (() -> Unit)?,
    actions: @Composable (centred: Modifier) -> Unit,
) {
    // Each control's middle on the title's first line's: the title's top padding and the middle of its
    // first line as laid out (a large font scales the line by its own curve, not the style's height).
    val padding = with(LocalDensity.current) { HEADING_PADDING.roundToPx() }
    var lineMiddle by remember { mutableIntStateOf(0) }
    Row {
        val centred = Modifier.alignBy { it.measuredHeight / 2 }
        Column(
            Modifier
                .weight(1f)
                .alignBy { padding + lineMiddle }
                .padding(vertical = HEADING_PADDING)
                .testTag("$tag:heading")
                .semantics(mergeDescendants = true) { heading() },
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                onTextLayout = { lineMiddle = ((it.getLineTop(0) + it.getLineBottom(0)) / 2).roundToInt() },
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("$tag:subtitle"),
            )
        }
        actions(centred)
        onClose?.let { PanelClose(tag, closeModifier.then(centred), it) }
    }
}

// A panel's *Close*, tagged `<tag>:close`, under [modifier] (a focus requester, an alignment).
@Composable
private fun PanelClose(
    tag: String,
    modifier: Modifier,
    onClose: () -> Unit,
) {
    TextButton(onClick = onClose, modifier = modifier.testTag("$tag:close")) {
        Text(stringResource(R.string.page_close), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The rule a panel draws under its heading ([Panel]), for a panel's content to draw where it sets a
 * part apart: what *Search with AI* found, under what the sources gave (#773); a genre unfolded
 * under *Genres*' chips (#786).
 */
@Composable
internal fun PanelRule(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = MaterialTheme.colorScheme.onSurface.copy(alpha = RULE))
}

// Above and below a panel's title.
private val HEADING_PADDING = 10.dp

private const val PANEL_FILL = 0.05f
private const val RULE = 0.12f
