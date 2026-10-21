package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.Settings

/**
 * The home screen's settings: *Keep Sources as you left them* (#269), off by default, so the
 * *Sources* list under each new track's insight starts folded.
 */
@Composable
internal fun HomeSection(
    settings: Settings,
    update: Update,
) {
    SectionHeading(R.string.settings_section_home)
    SwitchRow(
        title = stringResource(R.string.settings_keep_sources),
        summary = stringResource(R.string.settings_keep_sources_summary),
        checked = settings.keepSourcesUnfolded,
    ) { on -> update { it.copy(keepSourcesUnfolded = on) } }
}
