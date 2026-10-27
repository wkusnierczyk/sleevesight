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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R

// A panel that opens in a chip's place (#558, #623, #649): the frame a genre's panel and *Sources*'
// share on Home, and each section of the settings.

/**
 * A panel (frame B of the genre panel's mock-ups, #558; *Sources*' on Home since #623, and each
 * section of the settings since #649): on a light fill, rounded, [title] as a heading, with *Close*
 * at the top right when there is [onClose] (with [closeModifier], such as a focus requester); under
 * a rule, [content], its items [spacing] apart. [headingFrame] wraps the heading's row, the title
 * and *Close*: in the settings, where a link to the section lands. [modifier] goes on the outside.
 * Tagged [tag] for tests, its heading `<tag>:heading` and its *Close* `<tag>:close`.
 */
@Suppress("LongParameterList") // What the panel shows, and where its users hook into it.
@Composable
internal fun Panel(
    title: String,
    tag: String,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    closeModifier: Modifier = Modifier,
    spacing: Dp = 10.dp,
    headingFrame: @Composable (@Composable () -> Unit) -> Unit = { it() },
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val fill = MaterialTheme.colorScheme.onSurface.copy(alpha = PANEL_FILL)
    Column(
        modifier =
            modifier
                .testTag(tag)
                .fillMaxWidth()
                .clip(shape)
                .background(fill, shape)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        headingFrame {
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
                onClose?.let {
                    TextButton(onClick = it, modifier = closeModifier.testTag("$tag:close")) {
                        Text(stringResource(R.string.page_close), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Column(modifier = Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(spacing)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = RULE))
            content()
        }
    }
}

private const val PANEL_FILL = 0.05f
private const val RULE = 0.12f
