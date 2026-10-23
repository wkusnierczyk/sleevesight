package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

private const val DISABLED_ALPHA = 0.38f

@Composable
internal fun SectionHeading(
    @StringRes text: Int,
) {
    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
internal fun Hint(
    @StringRes text: Int,
) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
}

/**
 * A setting that is on or off; the whole row toggles it. A null [onChange] is disabled. [highlight]
 * sets its title in the theme's error colour, for a privacy choice (#420).
 */
@Suppress("LongParameterList") // Each sets one aspect of the row; all but the first three have defaults.
@Composable
internal fun SwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    indent: Boolean = false,
    highlight: Boolean = false,
    onChange: ((Boolean) -> Unit)?,
) {
    val enabled = onChange != null
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    val titleColor = if (highlight) MaterialTheme.colorScheme.error.copy(alpha = alpha) else color
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { onChange?.invoke(it) },
                ).padding(start = if (indent) 16.dp else 0.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A setting with a value, which [onClick] opens a dialog to change; null [onClick] is disabled. */
@Composable
internal fun ValueRow(
    title: String,
    value: String,
    summary: String? = null,
    indent: Boolean = false,
    onClick: (() -> Unit)?,
) {
    val alpha = if (onClick != null) 1f else DISABLED_ALPHA
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
                .padding(start = if (indent) 16.dp else 0.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        )
        summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
    }
}

/** One option in a dialog's list; the whole row selects it. */
@Composable
internal fun RadioRow(
    label: String,
    selected: Boolean,
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
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}
