package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically

/**
 * *Saver* (#203, #444): a switch, on while automatic writing pauses after a number of track
 * changes while Sleevesight is not on screen (`pauseAutoWriteAfter`), and under it, while on, that
 * number, *n*; a value stored that is not among the choices is offered too. Switched on, it pauses
 * after the default number ([Settings.DEFAULT_PAUSE_AUTO_WRITE_AFTER]). Fixed while nothing is
 * written automatically (*Show insights* off, or *Reactive* under *Manual*).
 */
@Composable
internal fun SaverRows(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    val writes = settings.writeInsights && settings.writeAutomatically != WriteAutomatically.NEVER
    val count = settings.pauseAutoWriteAfter
    SwitchRow(
        title = stringResource(R.string.settings_pause_auto_write),
        summary = stringResource(R.string.settings_pause_auto_write_summary),
        checked = count != null,
        onChange =
            { on: Boolean ->
                update { it.copy(pauseAutoWriteAfter = if (on) Settings.DEFAULT_PAUSE_AUTO_WRITE_AFTER else null) }
            }.takeIf { writes },
    )
    if (count == null) return
    val options = (Settings.PAUSE_AUTO_WRITE_CHOICES + count).distinct().sorted().map { it to pauseLabel(it) }
    ValueRow(
        title = stringResource(R.string.settings_pause_auto_write_after),
        value = pauseLabel(count),
        summary = pauseSummary(count, settings.lookUpInBackground),
        indent = true,
        onClick =
            {
                open(
                    SettingsDialog.Choice(R.string.settings_pause_auto_write_after, options, count) { n ->
                        update { it.copy(pauseAutoWriteAfter = n) }
                    },
                )
            }.takeIf { writes },
    )
}

/**
 * What the count chosen, [count], does. With *Look up in the background* off ([background] false),
 * nothing is written away, so the count decides whether writing goes on once the app is back.
 */
@Composable
private fun pauseSummary(
    count: Int,
    background: Boolean,
): String {
    val plural =
        if (background) {
            R.plurals.settings_pause_auto_write_summary_count
        } else {
            R.plurals.settings_pause_auto_write_summary_count_foreground
        }
    return pluralStringResource(plural, count, count)
}

/** A choice of *Saver*'s count: [count] track changes away. */
@Composable
private fun pauseLabel(count: Int): String = pluralStringResource(R.plurals.settings_track_changes_away, count, count)
