package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically

/**
 * *Pause automatic writing after* (#203): *Off*, or a number of track changes while Sleevesight is
 * not on screen; a value stored that is not among the choices is offered too. Fixed while nothing
 * is written automatically (*Write insights* off, or *Write insights automatically* under *Never*).
 */
@Composable
internal fun PauseAutoWriteRow(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    val counts = (Settings.PAUSE_AUTO_WRITE_CHOICES + listOfNotNull(settings.pauseAutoWriteAfter)).distinct().sorted()
    val options =
        listOf<Pair<Int?, String>>(null to stringResource(R.string.settings_pause_auto_write_off)) +
            counts.map { it to pauseLabel(it) }
    ValueRow(
        title = stringResource(R.string.settings_pause_auto_write),
        value = options.first { it.first == settings.pauseAutoWriteAfter }.second,
        summary = pauseSummary(settings.pauseAutoWriteAfter, settings.lookUpInBackground),
        onClick =
            {
                val chosen = settings.pauseAutoWriteAfter
                open(
                    SettingsDialog.Choice(R.string.settings_pause_auto_write, options, chosen) { n ->
                        update { it.copy(pauseAutoWriteAfter = n) }
                    },
                )
            }.takeIf { settings.writeInsights && settings.writeAutomatically != WriteAutomatically.NEVER },
    )
}

/**
 * What *Pause automatic writing after* counts, then what the count chosen, [count], does: none
 * under *Off*, which never pauses. With *Look up in the background* off ([background] false),
 * nothing is written away, so the count decides whether writing goes on once the app is back.
 */
@Composable
private fun pauseSummary(
    count: Int?,
    background: Boolean,
): String {
    val counted = stringResource(R.string.settings_pause_auto_write_summary)
    if (count == null) return counted
    val plural =
        if (background) {
            R.plurals.settings_pause_auto_write_summary_count
        } else {
            R.plurals.settings_pause_auto_write_summary_count_foreground
        }
    return "$counted ${pluralStringResource(plural, count, count)}"
}

/** A choice of *Pause automatic writing after*: [count] track changes away. */
@Composable
private fun pauseLabel(count: Int): String = pluralStringResource(R.plurals.settings_track_changes_away, count, count)
