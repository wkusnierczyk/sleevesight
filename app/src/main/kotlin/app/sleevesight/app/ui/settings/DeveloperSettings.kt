package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.Settings

/**
 * *Developer* (#444), shown last and only in developer mode, which seven taps on *Version* turn on
 * ([VersionRow]): the place for whatever a developer or tester may want to set and a listener will
 * not. Each developer option goes here, and its setting is turned off with developer mode
 * ([Settings.withoutDeveloperMode]); where a developer option changes another section (the
 * players' package names), that section asks `Settings.developerMode`.
 *
 * - *Diagnostics*, off by default, and while it is on the way to the diagnostics screen
 *   ([openDiagnostics]; `design/design.md`, *Diagnostics*).
 * - *Developer mode*, which turns developer mode off, and with it every developer option, and
 *   hides the section again.
 */
@Composable
internal fun DeveloperSection(
    settings: Settings,
    update: Update,
    openDiagnostics: () -> Unit,
) {
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
    SwitchRow(
        title = stringResource(R.string.settings_developer_mode),
        summary = stringResource(R.string.settings_developer_mode_summary),
        checked = settings.developerMode,
    ) { on -> update { if (on) it.copy(developerMode = true) else it.withoutDeveloperMode() } }
}
