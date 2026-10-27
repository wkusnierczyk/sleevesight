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
 * opened. *Home guide* (#627): whether Home, with nothing playing, shows the guide to Home, where
 * its *Re-enable in Settings* lands. *Theme*, *Font* and *Layout* show their choices as button
 * groups (#637), one tap to choose.
 */
@Composable
internal fun AppearanceSection(
    settings: Settings,
    update: Update,
) {
    // *Theme*, *Font* and *Layout*: a few short choices each, as button groups (#637).
    ChoiceButtonsRow(
        title = stringResource(R.string.settings_theme),
        summary = stringResource(R.string.settings_theme_summary),
        choices = themes(),
        selected = settings.theme,
    ) { theme -> update { it.copy(theme = theme) } }
    ChoiceButtonsRow(
        title = stringResource(R.string.settings_font),
        summary = stringResource(R.string.settings_font_summary),
        choices = fonts(),
        selected = settings.font,
    ) { font -> update { it.copy(font = font) } }
    ChoiceButtonsRow(
        title = stringResource(R.string.settings_layout),
        summary = stringResource(R.string.settings_layout_summary),
        choices = layouts(),
        selected = settings.layout,
    ) { layout -> update { it.copy(layout = layout) } }
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
    // Where the home guide's *Re-enable in Settings* links to (#627).
    LandingSpot(SettingsTarget.homeGuide) {
        SwitchRow(
            title = stringResource(R.string.settings_home_guide),
            summary = stringResource(R.string.settings_home_guide_summary),
            checked = settings.homeGuide,
        ) { on -> update { it.copy(homeGuide = on) } }
    }
}

/** *Theme*'s choices. */
@Composable
internal fun themes(): List<Pair<ThemeChoice, String>> =
    listOf(
        ThemeChoice.SYSTEM to stringResource(R.string.settings_theme_system),
        ThemeChoice.LIGHT to stringResource(R.string.settings_theme_light),
        ThemeChoice.DARK to stringResource(R.string.settings_theme_dark),
    )

/** *Font*'s choices. */
@Composable
internal fun fonts(): List<Pair<FontChoice, String>> =
    listOf(
        FontChoice.APP_FONT to stringResource(R.string.settings_font_app),
        FontChoice.SYSTEM_FONT to stringResource(R.string.settings_font_system),
    )

/** *Layout*'s choices. */
@Composable
internal fun layouts(): List<Pair<Layout, String>> =
    listOf(
        Layout.EXPANDABLE_BOXES to stringResource(R.string.settings_layout_boxes),
        Layout.SEPARATE_PAGES to stringResource(R.string.settings_layout_pages),
    )
