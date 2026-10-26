package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.LinkStyle
import app.sleevesight.core.settings.ReportButton
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
 * - *Show grounding details* (#437), off by default: the grounding check's counts in full under
 *   each insight, where otherwise only a note shows, and only for a sentence the sources do not
 *   back; and under *Sources* every source's status, the `M` chip and the rejected matches (#440)
 *   (`Settings.showGroundingDetails`).
 * - *Report button* (#452, #518): Home's button for a tester's report ([Settings.reportButton]):
 *   *Show*, *Auto* (hidden while Home scrolls) or *Hide*; *Auto* by default in the test build and
 *   *Hide* in any other.
 * - *Link style* (#519): every link underlined (the default) or highlighted
 *   ([Settings.linkStyle]), to try both on the phone over real album art; back to the underline
 *   with developer mode off.
 * - *Developer mode*, which turns developer mode off, and with it every developer option, and
 *   hides the section again.
 */
@Composable
internal fun DeveloperSection(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
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
        title = stringResource(R.string.settings_grounding_details),
        summary = stringResource(R.string.settings_grounding_details_summary),
        checked = settings.showGroundingDetails,
    ) { on -> update { it.copy(showGroundingDetails = on) } }
    ReportButtonRow(settings, update, open)
    val linkStyles =
        listOf(
            LinkStyle.UNDERLINE to stringResource(R.string.settings_link_style_underline),
            LinkStyle.HIGHLIGHT to stringResource(R.string.settings_link_style_highlight),
        )
    ValueRow(
        title = stringResource(R.string.settings_link_style),
        value = linkStyles.first { it.first == settings.linkStyle }.second,
        summary = stringResource(R.string.settings_link_style_summary),
    ) {
        open(
            SettingsDialog.Choice(R.string.settings_link_style, linkStyles, settings.linkStyle) { style ->
                update { it.copy(linkStyle = style) }
            },
        )
    }
    SwitchRow(
        title = stringResource(R.string.settings_developer_mode),
        summary = stringResource(R.string.settings_developer_mode_summary),
        checked = settings.developerMode,
    ) { on -> update { if (on) it.copy(developerMode = true) else it.withoutDeveloperMode() } }
}

/** *Report button* (#452, #518): *Show*, *Auto* or *Hide*, each described in the dialog (#550). */
@Composable
private fun ReportButtonRow(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    val reportButtons =
        listOf(
            ReportButton.SHOW to stringResource(R.string.settings_report_button_show),
            ReportButton.AUTO to stringResource(R.string.settings_report_button_auto),
            ReportButton.HIDE to stringResource(R.string.settings_report_button_hide),
        )
    val reportButtonDetails =
        mapOf(
            ReportButton.SHOW to stringResource(R.string.settings_report_button_show_detail),
            ReportButton.AUTO to stringResource(R.string.settings_report_button_auto_detail),
            ReportButton.HIDE to stringResource(R.string.settings_report_button_hide_detail),
        )
    ValueRow(
        title = stringResource(R.string.settings_report_button),
        value = reportButtons.first { it.first == settings.reportButton }.second,
        summary = stringResource(R.string.settings_report_button_summary),
    ) {
        open(
            SettingsDialog.Choice(
                R.string.settings_report_button,
                reportButtons,
                settings.reportButton,
                details = reportButtonDetails,
            ) { choice ->
                update { it.copy(reportButton = choice) }
            },
        )
    }
}
