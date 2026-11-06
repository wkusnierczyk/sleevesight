@file:Suppress("TooManyFunctions") // The settings' kinds of row, and the parts they share.

package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sleevesight.app.ui.theme.ChoiceButtonGroup
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.plainSwitchColors
import kotlin.math.roundToInt

private const val DISABLED_ALPHA = 0.38f

/** How far a row nested under another starts in. */
private val NESTED = 16.dp

/** The gap between what starts a row ([ROW_MARK]) and its title. */
private val LEADING_GAP = 16.dp

/** A [Hint]'s padding above and below it. */
private val HINT_PADDING = 4.dp

/**
 * Where a row under a badged row starts (#537): at the badged row's name, past its badge and the
 * gap after it, as the model and effort rows under an AI service do.
 */
internal val UNDER_BADGE = ROW_MARK + LEADING_GAP

/** The test tag of what starts a [SwitchRow] or a [ValueRow], its [ROW_MARK] (#613). */
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

/**
 * A line of explanation in a section, [text], folded to one line as a row's description is
 * ([Description], #847). As a sub-heading over rows, [over] names them by
 * title: a search that shows only some rows shows it while any of them shows (#649); with none, it
 * is not a setting of its own, and such a search leaves it out. A blank line in [text] starts a
 * paragraph ([Paragraphs], #758). Not [foldable], a line saying a state rather than a description
 * (*No players yet*, #874), it shows in full.
 */
@Composable
internal fun Hint(
    @StringRes text: Int,
    over: List<String> = emptyList(),
    foldable: Boolean = true,
) {
    val search = LocalRowSearch.current
    if (search?.filtering == true && over.none { search.shows(it) }) return
    if (!foldable) {
        Paragraphs(stringResource(text), Modifier.padding(vertical = HINT_PADDING))
        return
    }
    // Folded to one line as a row's description is (#847), known by its string's name.
    val key = LocalResources.current.getResourceEntryName(text)
    // Its tap reaches into its own padding below it, not into the row after it (#874).
    Description(key, stringResource(text), Modifier.padding(vertical = HINT_PADDING), reach = HINT_PADDING)
}

/**
 * A setting that is on or off; the whole row toggles it. [highlight]
 * sets its title in the theme's error colour, for a privacy choice (#420). [below] goes under the
 * title and summary, as a link to the service the switch is for; a link there is a control of its
 * own, apart from the switch, and so is [summaryLink], a link set in the summary where it has the
 * link's text ([sentenceWith]): another setting it names (#581). [leading] goes at the row's start,
 * before the title, [ROW_MARK] a side: a player's icon (#535), a service's badge (#537).
 *
 * The mark and the switch are centred on the first line of the title (#613), not on the whole row:
 * level with the name however long the summary under it, at any font scale. A row with a name only
 * looks as when they were centred on the row.
 *
 * The summary folds to one line, a tap on it unfolding it ([Description], #847); that tap is its
 * own and does not toggle the switch. [note], under it, says why the switch cannot be moved, or
 * what it needs (*Needs your Exa key.*), and always shows in full, read with the switch.
 *
 * As a [ChoiceButtonsRow]'s `enabled` and `applies` (#947, #999): a null [onChange], the setting
 * still applies but something else holds its value (a key missing or no longer readable): only the
 * switch dims, its text, mark, note and [below] staying as they are, since they still say what
 * happens. Where it does not [apply][applies] while another setting is as it is, the whole row
 * dims, its text, mark and [below] too (a link there still works), and it cannot be changed.
 * Either way a screen reader reads it as disabled.
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
    note: String? = null,
    applies: Boolean = true,
    onChange: ((Boolean) -> Unit)?,
) {
    if (!shownInSearch(title)) return
    val enabled = onChange != null && applies
    val alpha = if (applies) 1f else DISABLED_ALPHA
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    val titleColor = if (highlight) MaterialTheme.colorScheme.error.copy(alpha = alpha) else color
    MarkedRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { onChange?.invoke(it) },
                ).padding(start = if (indent) NESTED else 0.dp, top = 8.dp, bottom = 8.dp),
        title = title,
        titleColor = titleColor,
        alpha = alpha,
        leading = leading,
        trailing = {
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier.alignBy(::middle).testTag(ROW_SWITCH_TAG),
                enabled = enabled,
                colors = plainSwitchColors(),
            )
        },
    ) {
        // Its tap reaches into the row's padding where nothing comes under it (#874).
        val reach = if (note == null && below == null) TAP_BELOW else 0.dp
        summary?.let { Description(title, it, color = color, link = summaryLink, reach = reach) }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
        // Dimmed with the row where the setting does not apply (#999); a link in it still works.
        below?.let { Column(Modifier.alpha(alpha)) { it() } }
    }
}

/**
 * A row that does something once rather than holding a value (#759): its [title] and [summary], and
 * where a [SwitchRow] has its switch, a small outlined button, [label], lined up with the title's
 * first line as a switch is; [onClick] runs on a tap on it, and a null [onClick] disables it. Only
 * the button is a control: a screen reader reads it by the row's name, and says when it is
 * disabled.
 */
@Composable
internal fun ButtonRow(
    title: String,
    summary: String,
    label: String,
    tag: String,
    onClick: (() -> Unit)?,
) {
    if (!shownInSearch(title)) return
    val color = MaterialTheme.colorScheme.onSurface
    MarkedRow(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        title = title,
        titleColor = color,
        alpha = 1f,
        leading = null,
        trailing = {
            Spacer(Modifier.width(16.dp))
            OutlinedButton(
                onClick = { onClick?.invoke() },
                enabled = onClick != null,
                modifier = Modifier.alignBy(::middle).testTag(tag).semantics { contentDescription = title },
            ) { Text(label) }
        },
    ) {
        Description(title, summary, color = color, reach = TAP_BELOW)
    }
}

/**
 * A settings row's layout (#613, #670): [leading], when given, at its start, [ROW_MARK] a side and
 * [alpha] dimmed with the row's text; then [title], in [titleColor], with [content] under it; then
 * [trailing], when given, at its end. What [leading] and [trailing] hold is lined up with the middle
 * of the title's first line ([middle]), not the whole row, so it stays level with the name however
 * long what is under it, at any font scale.
 */
@Suppress("LongParameterList") // The row's parts and how they look, each a value of its own.
@Composable
private fun MarkedRow(
    modifier: Modifier,
    title: String,
    titleColor: Color,
    alpha: Float,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val firstLine = remember { FirstLine() }
    Row(modifier = modifier) {
        leading?.let {
            // Dimmed with the row's text when the row cannot be used.
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
            content()
        }
        trailing?.invoke(this)
    }
}

/**
 * A setting with a value, which [onClick] opens a dialog to change; null [onClick] is disabled.
 * [indent] nests it under the row above; [underBadge] starts it at the name of the badged row above
 * ([UNDER_BADGE]) instead. [leading] goes at the row's start, before the title, [ROW_MARK] a side,
 * centred on the title's first line as a [SwitchRow]'s is: a source's badge (#670). The summary
 * folds ([Description], #847); [note], under it, says the setting's state (a model found
 * unavailable), or what the value chosen does (*Stop after*, #874), and always shows in full.
 */
@Suppress("LongParameterList") // Each sets one aspect of the row; all but the title, value and action have defaults.
@Composable
internal fun ValueRow(
    title: String,
    value: String,
    summary: String? = null,
    note: String? = null,
    indent: Boolean = false,
    underBadge: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)?,
) {
    if (!shownInSearch(title)) return
    val alpha = if (onClick != null) 1f else DISABLED_ALPHA
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    MarkedRow(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
                .padding(start = valueRowStart(indent, underBadge), top = 8.dp, bottom = 8.dp),
        title = title,
        titleColor = color,
        alpha = alpha,
        leading = leading,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        )
        // Its tap reaches into the row's padding where no note comes under it (#874).
        summary?.let { Description(title, it, color = color, reach = if (note == null) TAP_BELOW else 0.dp) }
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.testTag(ROW_NOTE_TAG),
            )
        }
    }
}

/** The test tag of a [ValueRow]'s [note][ValueRow] (#847). */
internal const val ROW_NOTE_TAG = "settings_row_note"

/**
 * A setting with a few short choices (#637): [title], then [summary], then its [choices] as a
 * connected button group ([ChoiceButtonGroup]), the [selected] one marked; a tap on another calls
 * [onSelect] with it, at once, no dialog. Each button is read with [title] ("Theme: Light"). A
 * value in [details] is read with its button, and has a line under the group saying what it does
 * while it is the one chosen ([ChoiceDetails]). The buttons stay in one row at any font size
 * (#821). A setting whose choices are many or long, or described one by one, is a
 * [ValueRow] opening a dialog instead (`design/design.md`, *Settings screen*). The summary folds
 * ([Description], #847); the line under the buttons does not. Not [enabled], none
 * can be chosen; a value in [disabled], that one; [below] comes under the line of what the value
 * does, dimmed with the row. A value in [spoken] is read by that in place of its label ([ChoiceButtonGroup]). A
 * [summaryTable], given in place of [summary], is a description laid out as a table
 * ([DescriptionTable], *Reactive*'s, #945). Not [enabled], the setting still applies but another
 * one sets its value: only the buttons dim. Where it does not [apply][applies] while another
 * setting is as it is, the whole row dims, its text too, and none can be chosen (#947).
 */
@Suppress("LongParameterList") // What the row shows, each a value of its own, and what a tap does.
@Composable
internal fun <T> ChoiceButtonsRow(
    title: String,
    summary: String?,
    choices: List<Pair<T, String>>,
    selected: T,
    details: Map<T, String> = emptyMap(),
    enabled: Boolean = true,
    disabled: Set<T> = emptySet(),
    below: (@Composable () -> Unit)? = null,
    spoken: Map<T, String> = emptyMap(),
    summaryTable: DescriptionTable? = null,
    applies: Boolean = true,
    onSelect: (T) -> Unit,
) {
    if (!shownInSearch(title)) return
    val alpha = if (applies) 1f else DISABLED_ALPHA
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        Text(
            searchMarked(title),
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            modifier = Modifier.testTag(ROW_TITLE_TAG),
        )
        // Its tap reaches no further than the buttons' room above them, which is theirs (#874).
        summary?.let { Description(title, it, color = color, reach = ABOVE_GROUP) }
        summaryTable?.let { Description(title, it.text, color = color, reach = ABOVE_GROUP, table = it) }
        ChoiceButtonGroup(
            choices,
            selected,
            onSelect,
            Modifier.padding(top = ABOVE_GROUP),
            name = title,
            details = details,
            enabled = enabled && applies,
            disabled = disabled,
            spoken = spoken,
        )
        ChoiceDetails(choices, selected, details, alpha)
        // Dimmed with the row where the setting does not apply; a link in it still works.
        below?.let { Column(Modifier.alpha(alpha)) { it() } }
    }
}

/** The room above a [ChoiceButtonsRow]'s buttons, under its description. */
internal val ABOVE_GROUP = 4.dp

/**
 * Under a [ChoiceButtonsRow]'s buttons, what the [selected] value does, from [details], announced
 * as it changes (a polite live region). While a search finds the description of a value not
 * chosen, that one shows too, by its label, the query marked in it, so what the search found is
 * on the screen.
 */
@Composable
private fun <T> ChoiceDetails(
    choices: List<Pair<T, String>>,
    selected: T,
    details: Map<T, String>,
    alpha: Float,
) {
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
    details[selected]?.let {
        Text(it, style = style, color = color, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    val needle =
        LocalRowSearch.current
            ?.query
            ?.trim()
            .orEmpty()
    if (needle.isEmpty()) return
    for ((value, label) in choices) {
        val detail = details[value]
        if (value != selected && detail != null && detail.contains(needle, ignoreCase = true)) {
            Text(searchMarked("$label: $detail"), style = style, color = color)
        }
    }
}

/**
 * One option in a dialog's list; the whole row selects it. [detail], when given, goes under the
 * label, smaller and muted: a model's id under its name. The row is one selectable node, so a
 * screen reader reads the detail with its label.
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
