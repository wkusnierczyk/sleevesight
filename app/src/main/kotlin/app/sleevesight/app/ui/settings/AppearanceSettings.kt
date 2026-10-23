package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.FontChoice
import app.sleevesight.core.settings.Settings

/**
 * How the app looks: *Font* (#377), the app's own font, Roboto, or the phone's system font, a
 * change showing at once, in the whole app; and *Logo intro at start* (#383), from the next time
 * the app is opened.
 */
@Composable
internal fun AppearanceSection(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    SectionHeading(R.string.settings_section_appearance)
    val fonts =
        listOf(
            FontChoice.APP_FONT to stringResource(R.string.settings_font_app),
            FontChoice.SYSTEM_FONT to stringResource(R.string.settings_font_system),
        )
    ValueRow(
        title = stringResource(R.string.settings_font),
        value = fonts.first { it.first == settings.font }.second,
        summary = stringResource(R.string.settings_font_summary),
    ) {
        open(
            SettingsDialog.Choice(R.string.settings_font, fonts, settings.font) { font ->
                update { it.copy(font = font) }
            },
        )
    }
    SwitchRow(
        title = stringResource(R.string.settings_intro),
        summary = stringResource(R.string.settings_intro_summary),
        checked = settings.introAtStart,
    ) { on -> update { it.copy(introAtStart = on) } }
}
