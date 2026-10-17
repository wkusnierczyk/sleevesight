package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.Settings

/**
 * The developer settings, last: *Diagnostics*, off by default, and while it is on the way to the
 * diagnostics screen ([openDiagnostics]; `design/design.md`, *Diagnostics*).
 */
@Composable
internal fun DeveloperSection(
    settings: Settings,
    update: Update,
    openDiagnostics: () -> Unit,
) {
    SectionHeading(R.string.settings_section_developer)
    SwitchRow(
        title = stringResource(R.string.settings_diagnostics),
        summary = stringResource(R.string.settings_diagnostics_summary),
        checked = settings.showDiagnostics,
    ) { on -> update { it.copy(showDiagnostics = on) } }
    if (settings.showDiagnostics) {
        ValueRow(
            title = stringResource(R.string.settings_diagnostics_open),
            value = stringResource(R.string.settings_diagnostics_open_value),
            indent = true,
            onClick = openDiagnostics,
        )
    }
}
