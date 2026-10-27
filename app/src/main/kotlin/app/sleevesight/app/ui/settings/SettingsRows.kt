package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.plainSwitchColors
import kotlin.math.roundToInt

private const val DISABLED_ALPHA = 0.38f

/** How far a row nested under another starts in. */
private val NESTED = 16.dp

/** The gap between what starts a row ([ROW_MARK]) and its title. */
private val LEADING_GAP = 16.dp

/**
 * Where a row under a badged row starts (#537): at the badged row's name, past its badge and the
 * gap after it, as the model and effort rows under an AI service do.
 */
internal val UNDER_BADGE = ROW_MARK + LEADING_GAP

/** The test tag of what starts a [SwitchRow], its [ROW_MARK] (#613). */
internal const val ROW_LEADING_TAG = "settings-row-leading"

/** The test tag of a [SwitchRow]'s switch (#613). */
internal const val ROW_SWITCH_TAG = "settings-row-switch"

/** Where a row's mark or switch is lined up with its name: its middle (#613). */
private fun middle(measured: Measured): Int = measured.measuredHeight / 2

/**
 * The middle of a row's title's first line, in pixels from the title's top, as the title was last
 * laid out (#613). Read where the row lines up its mark and switch, after the title is measured;
 * not state, since it changes only while the row is being measured.
 */
private class FirstLine {
    var middle = 0
}

@Composable
internal fun Hint(
    @StringRes text: Int,
) {
    // Not a setting of its own: a search showing only some rows leaves it out (#649).
    if (LocalRowSearch.current?.filtering == true) return
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
}

/**
 * A setting that is on or off; the whole row toggles it. A null [onChange] is disabled. [highlight]
 * sets its title in the theme's error colour, for a privacy choice (#420). [below] goes under the
 * title and summary, as a link to the service the switch is for; a link there is a control of its
 * own, apart from the switch, and so is [summaryLink], a link set in the summary where it has the
 * link's text ([sentenceWith]): another setting it names (#581). [leading] goes at the row's start,
 * before the title, [ROW_MARK] a side: a player's icon (#535), a service's badge (#537).
 *
 * The mark and the switch are centred on the first line of the title (#613), not on the whole row:
 * level with the name however long the summary under it, at any font scale. A row with a name only
 * looks as when they were centred on the row.
 */
@Suppress("LongParameterList") // Each sets one aspect of the row; all but the first three have defaults.
@Composable
internal fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    indent: Boolean = false,
    highlight: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
    summaryLink: TextPart.Linked? = null,
    onChange: ((Boolean) -> Unit)?,
) {
    if (!shownInSearch(title)) return
    val enabled = onChange != null
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    val titleColor = if (highlight) MaterialTheme.colorScheme.error.copy(alpha = alpha) else color
    val firstLine = remember { FirstLine() }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { onChange?.invoke(it) },
                ).padding(start = if (indent) NESTED else 0.dp, top = 8.dp, bottom = 8.dp),
    ) {
        leading?.let {
            // Dimmed with the row's text when the switch cannot be moved.
            Box(Modifier.alignBy(::middle).testTag(ROW_LEADING_TAG).alpha(alpha)) { it() }
            Spacer(Modifier.width(LEADING_GAP))
        }
        Column(modifier = Modifier.weight(1f).alignBy { firstLine.middle }) {
            Text(
                searchMarked(title),
                modifier = Modifier.testTag(ROW_TITLE_TAG),
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                onTextLayout = { firstLine.middle = ((it.getLineTop(0) + it.getLineBottom(0)) / 2).roundToInt() },
            )
            summary?.let { Summary(it, summaryLink, color) }
            below?.invoke()
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.alignBy(::middle).testTag(ROW_SWITCH_TAG),
            enabled = enabled,
            colors = plainSwitchColors(),
        )
    }
}

/** A row's [summary], in [color], with [link] set in it where it has the link's text. */
@Composable
private fun Summary(
    summary: String,
    link: TextPart.Linked?,
    color: Color,
) {
    if (link == null) {
        Text(summary, style = MaterialTheme.typography.bodySmall, color = color)
    } else {
        LinkedText(sentenceWith(summary, link), style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * A setting with a value, which [onClick] opens a dialog to change; null [onClick] is disabled.
 * [indent] nests it under the row above; [underBadge] starts it at the name of the badged row above
 * ([UNDER_BADGE]) instead.
 */
@Suppress("LongParameterList") // Each sets one aspect of the row; all but the title, value and action have defaults.
@Composable
internal fun ValueRow(
    title: String,
    value: String,
    summary: String? = null,
    indent: Boolean = false,
    underBadge: Boolean = false,
    onClick: (() -> Unit)?,
) {
    if (!shownInSearch(title)) return
    val alpha = if (onClick != null) 1f else DISABLED_ALPHA
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
                .padding(start = valueRowStart(indent, underBadge), top = 8.dp, bottom = 8.dp),
    ) {
        Text(
            searchMarked(title),
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            modifier = Modifier.testTag(ROW_TITLE_TAG),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        )
        summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
    }
}

/**
 * One option in a dialog's list; the whole row selects it. [detail], when given, goes under the
 * label, smaller and muted: a model's id under its name, or what a choice does (#546, #550). The row
 * is one selectable node, so a screen reader reads the detail with its label.
 */
@Composable
internal fun RadioRow(
    label: String,
    selected: Boolean,
    detail: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Where a [ValueRow] starts: at the badged row's name [underBadge], else nested by [indent]. */
private fun valueRowStart(
    indent: Boolean,
    underBadge: Boolean,
): Dp =
    when {
        underBadge -> UNDER_BADGE
        indent -> NESTED
        else -> 0.dp
    }

/** The test tag of a setting's title, as its row shows it, which the search finds it by (#649). */
internal const val ROW_TITLE_TAG = "settings_row_title"
