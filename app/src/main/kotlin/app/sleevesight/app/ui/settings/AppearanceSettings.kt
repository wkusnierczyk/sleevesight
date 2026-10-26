package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.FontChoice
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.ThemeChoice

/**
 * How the app looks: *Theme* (#444), light or dark as the system is, or always one of them;
 * *Font* (#377), the app's own font, Roboto, or the phone's system font; both showing at once, in
 * the whole app. *Layout* (#447): whether *Sources* and a genre's panel open in place on Home
 * (*Expandable boxes*, the default) or each on a page of its own (*Separate pages*). *Keep Sources
 * as you left them* (#269), off by default, so the *Sources* list under each new track's insight
 * starts folded; under *Expandable boxes* only, so it is shown, but not offered, under *Separate
 * pages*, where each page opens fresh. *Logo intro at start* (#383), from the next time the app is
 * opened.
 */
@Composable
internal fun AppearanceSection(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    val themes =
        listOf(
            ThemeChoice.SYSTEM to stringResource(R.string.settings_theme_system),
            ThemeChoice.LIGHT to stringResource(R.string.settings_theme_light),
            ThemeChoice.DARK to stringResource(R.string.settings_theme_dark),
        )
    ValueRow(
        title = stringResource(R.string.settings_theme),
        value = themes.first { it.first == settings.theme }.second,
        summary = stringResource(R.string.settings_theme_summary),
    ) {
        open(
            SettingsDialog.Choice(R.string.settings_theme, themes, settings.theme) { theme ->
                update { it.copy(theme = theme) }
            },
        )
    }
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
    LayoutRow(settings, update, open)
    val boxes = settings.layout == Layout.EXPANDABLE_BOXES
    SwitchRow(
        title = stringResource(R.string.settings_keep_sources),
        summary = stringResource(R.string.settings_keep_sources_summary),
        checked = settings.keepSourcesUnfolded,
        onChange = if (boxes) ({ on -> update { it.copy(keepSourcesUnfolded = on) } }) else null,
    )
    SwitchRow(
        title = stringResource(R.string.settings_intro),
        summary = stringResource(R.string.settings_intro_summary),
        checked = settings.introAtStart,
    ) { on -> update { it.copy(introAtStart = on) } }
}

/** *Layout* (#447): *Expandable boxes* or *Separate pages*, chosen in a dialog. */
@Composable
private fun LayoutRow(
    settings: Settings,
    update: Update,
    open: (SettingsDialog) -> Unit,
) {
    val layouts =
        listOf(
            Layout.EXPANDABLE_BOXES to stringResource(R.string.settings_layout_boxes),
            Layout.SEPARATE_PAGES to stringResource(R.string.settings_layout_pages),
        )
    ValueRow(
        title = stringResource(R.string.settings_layout),
        value = layouts.first { it.first == settings.layout }.second,
        summary = stringResource(R.string.settings_layout_summary),
    ) {
        open(
            SettingsDialog.Choice(R.string.settings_layout, layouts, settings.layout) { layout ->
                update { it.copy(layout = layout) }
            },
        )
    }
}
