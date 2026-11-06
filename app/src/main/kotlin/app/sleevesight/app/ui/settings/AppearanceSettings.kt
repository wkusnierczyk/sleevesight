package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.LocalMessages
import app.sleevesight.core.settings.DescriptionsOnReturn
import app.sleevesight.core.settings.FontChoice
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.LogoSpeed
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.ThemeChoice
import kotlinx.coroutines.Deferred

/**
 * How the app looks: *Theme* (#444), light or dark as the system is, or always one of them;
 * *Font* (#377), the app's own font, Roboto, or the phone's system font; both showing at once, in
 * the whole app. *Theme button* (#636), on by default: whether Home's header shows a button beside
 * the cogwheel that moves *Theme* one step on with each tap, which *Theme*'s row then shows.
 * *Layout* (#447): whether *Sources*, a genre's panel and the song's, the artist's and the album's
 * insights (#522) open in place on Home (*Expandable boxes*, the default) or each on a page of its
 * own (*Separate pages*). *Sources on a new track* (#269; a choice since #522, the switch *Keep
 * Sources as you left them* before) and *Song, Artist and Album on a new track* (#522): *Folded*
 * (the default), *Unfolded* or *As left*, as button groups; under *Expandable boxes* only,
 * so they are shown, but not offered, under *Separate pages*, where each page opens fresh. Then
 * *Foldable descriptions* and *Descriptions on return*
 * (#847, [DescriptionRows]). *Lyrics links* (#728), on by default: whether Home shows a
 * *Lyrics* button for the playing track, with links to its lyrics pages or a Genius search; under it,
 * indented, *Search Genius when there is no link*, on by default, which can be moved only while
 * *Lyrics links* is on, as *Sources on a new track* only under *Expandable boxes*. *Logo intro
 * at start* (#383), from the next time the app is opened; then *Spin the logo while music plays* and
 * *Logo speed* (#848, [LogoSpinRows]). *Theme*, *Font* and *Layout* show their choices as button
 * groups (#637), one tap to choose, as do the two on a new track ([NewTrackRows]). Last, *Restore
 * dismissed messages* (#759): its
 * button brings back everything dismissed for good ([restore]: the guide to Home, *Players*' note),
 * then a message says so; disabled while nothing is dismissed.
 */
@Composable
internal fun AppearanceSection(
    settings: Settings,
    update: Update,
    restore: () -> Deferred<Boolean>,
) {
    // *Theme*, *Font* and *Layout*: a few short choices each, as button groups (#637).
    ChoiceButtonsRow(
        title = stringResource(R.string.settings_theme),
        summary = stringResource(R.string.settings_theme_summary),
        choices = themes(),
        selected = settings.theme,
    ) { theme -> update { it.copy(theme = theme) } }
    SwitchRow(
        title = stringResource(R.string.settings_theme_button),
        summary = stringResource(R.string.settings_theme_button_summary),
        checked = settings.themeButton,
    ) { on -> update { it.copy(themeButton = on) } }
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
    NewTrackRows(settings, update)
    DescriptionRows(settings, update)
    LyricsRows(settings, update)
    SwitchRow(
        title = stringResource(R.string.settings_intro),
        summary = stringResource(R.string.settings_intro_summary),
        checked = settings.introAtStart,
    ) { on -> update { it.copy(introAtStart = on) } }
    LogoSpinRows(settings, update)
    // Last (#759), where *Home guide* was: disabled while nothing is dismissed.
    val messages = LocalMessages.current
    val restored = stringResource(R.string.settings_restore_dismissed_done)
    ButtonRow(
        title = stringResource(R.string.settings_restore_dismissed),
        summary = stringResource(R.string.settings_restore_dismissed_summary),
        label = stringResource(R.string.settings_restore_dismissed_button),
        tag = RESTORE_DISMISSED_TAG,
        onClick =
            if (settings.dismissed.isEmpty()) {
                null
            } else {
                { messages?.showOnceSaved(restore(), restored) }
            },
    )
}

/**
 * *Foldable descriptions* (#847), on by default: whether the settings' descriptions show on one line
 * ([Description]); then *Descriptions on return*, *Folded*, *Unfolded* or *As left* (the default)
 * as a button group, with a line on what the one chosen does ([DescriptionFolds]), dimmed as a
 * whole, as not applying, and keeping its value while the switch is off (#999).
 */
@Composable
private fun DescriptionRows(
    settings: Settings,
    update: Update,
) {
    SwitchRow(
        title = stringResource(R.string.settings_fold_descriptions),
        summary = stringResource(R.string.settings_fold_descriptions_summary),
        checked = settings.foldDescriptions,
    ) { on -> update { it.copy(foldDescriptions = on) } }
    ChoiceButtonsRow(
        title = stringResource(R.string.settings_descriptions_on_return),
        summary = stringResource(R.string.settings_descriptions_on_return_summary),
        choices = descriptionsOnReturn(),
        selected = settings.descriptionsOnReturn,
        details = descriptionsOnReturnDetails(),
        // Nothing folds: the setting does not apply (#999).
        applies = settings.foldDescriptions,
    ) { choice -> update { it.copy(descriptionsOnReturn = choice) } }
}

/** *Descriptions on return*'s choices. */
@Composable
internal fun descriptionsOnReturn(): List<Pair<DescriptionsOnReturn, String>> =
    listOf(
        DescriptionsOnReturn.FOLDED to stringResource(R.string.settings_descriptions_folded),
        DescriptionsOnReturn.UNFOLDED to stringResource(R.string.settings_descriptions_unfolded),
        DescriptionsOnReturn.AS_LEFT to stringResource(R.string.settings_descriptions_as_left),
    )

/** What each of *Descriptions on return*'s choices does, as its row says under the buttons. */
@Composable
internal fun descriptionsOnReturnDetails(): Map<DescriptionsOnReturn, String> =
    mapOf(
        DescriptionsOnReturn.FOLDED to stringResource(R.string.settings_descriptions_folded_detail),
        DescriptionsOnReturn.UNFOLDED to stringResource(R.string.settings_descriptions_unfolded_detail),
        DescriptionsOnReturn.AS_LEFT to stringResource(R.string.settings_descriptions_as_left_detail),
    )

/**
 * *Lyrics links* (#728), and under it, indented, *Search Genius when there is no link*, which keeps
 * its value but cannot be moved while the links are off.
 */
@Composable
private fun LyricsRows(
    settings: Settings,
    update: Update,
) {
    SwitchRow(
        title = stringResource(R.string.settings_lyrics_links),
        summary = stringResource(R.string.settings_lyrics_links_summary),
        checked = settings.lyricsLinks,
    ) { on -> update { it.copy(lyricsLinks = on) } }
    // Landed on from Home's *Lyrics*, where the search is off (#728).
    LandingSpot(SettingsTarget.lyricsSearch) {
        SwitchRow(
            title = stringResource(R.string.settings_lyrics_search),
            summary = null,
            checked = settings.lyricsSearch,
            indent = true,
            // With no lyrics links there is nothing to search for: the setting does not apply.
            applies = settings.lyricsLinks,
        ) { on -> update { it.copy(lyricsSearch = on) } }
    }
}

/**
 * *Spin the logo while music plays* (#848), on by default, and under it *Logo speed*, 33⅓ rpm or
 * 45 rpm as a button group, with a line on the record each speed is; the speed keeps its value but
 * does not apply while the switch is off, so its row dims as a whole and cannot be changed (#999).
 * A screen reader says "33⅓ rpm" in words.
 */
@Composable
private fun LogoSpinRows(
    settings: Settings,
    update: Update,
) {
    SwitchRow(
        title = stringResource(R.string.settings_spin_logo),
        summary = stringResource(R.string.settings_spin_logo_summary),
        checked = settings.spinLogo,
    ) { on -> update { it.copy(spinLogo = on) } }
    ChoiceButtonsRow(
        title = stringResource(R.string.settings_logo_speed),
        summary = stringResource(R.string.settings_logo_speed_summary),
        choices = logoSpeeds(),
        selected = settings.logoSpeed,
        details =
            mapOf(
                LogoSpeed.RPM_33 to stringResource(R.string.settings_logo_speed_33_detail),
                LogoSpeed.RPM_45 to stringResource(R.string.settings_logo_speed_45_detail),
            ),
        // The logo does not spin: its speed does not apply (#999).
        applies = settings.spinLogo,
        spoken = mapOf(LogoSpeed.RPM_33 to stringResource(R.string.settings_logo_speed_33_spoken)),
    ) { speed -> if (settings.spinLogo) update { it.copy(logoSpeed = speed) } }
}

/** The test tag of *Restore dismissed messages*' button (#759). */
internal const val RESTORE_DISMISSED_TAG = "settings:restoreDismissed"

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

/** *Logo speed*'s choices (#848). */
@Composable
internal fun logoSpeeds(): List<Pair<LogoSpeed, String>> =
    listOf(
        LogoSpeed.RPM_33 to stringResource(R.string.settings_logo_speed_33),
        LogoSpeed.RPM_45 to stringResource(R.string.settings_logo_speed_45),
    )

/** *Layout*'s choices. */
@Composable
internal fun layouts(): List<Pair<Layout, String>> =
    listOf(
        Layout.EXPANDABLE_BOXES to stringResource(R.string.settings_layout_boxes),
        Layout.SEPARATE_PAGES to stringResource(R.string.settings_layout_pages),
    )
