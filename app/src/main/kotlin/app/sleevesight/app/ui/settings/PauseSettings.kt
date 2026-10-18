package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.Settings

/**
 * *Pause automatic writing after* (#203): *Off*, or a number of track changes while Sleevesight is
 * not on screen; a value stored that is not among the choices is offered too.
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
        summary = stringResource(R.string.settings_pause_auto_write_summary),
        onClick =
            {
                val chosen = settings.pauseAutoWriteAfter
                open(
                    SettingsDialog.Choice(R.string.settings_pause_auto_write, options, chosen) { n ->
                        update { it.copy(pauseAutoWriteAfter = n) }
                    },
                )
            }.takeIf { settings.writeInsights },
    )
}

/** A choice of *Pause automatic writing after*: [count] track changes away. */
@Composable
private fun pauseLabel(count: Int): String = pluralStringResource(R.plurals.settings_track_changes_away, count, count)
