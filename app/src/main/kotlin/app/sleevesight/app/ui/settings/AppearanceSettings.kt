package app.sleevesight.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.LocalMessages
import app.sleevesight.core.settings.FontChoice
import app.sleevesight.core.settings.Layout
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.ThemeChoice
import kotlinx.coroutines.Deferred

/**
 * How the app looks: *Theme* (#444), light or dark as the system is, or always one of them;
 * *Font* (#377), the app's own font, Roboto, or the phone's system font; both showing at once, in
 * the whole app. *Theme button* (#636), on by default: whether Home's header shows a button beside
 * the cogwheel that moves *Theme* one step on with each tap, which *Theme*'s row then shows.
 * *Layout* (#447): whether *Sources* and a genre's panel open in place on Home
 * (*Expandable boxes*, the default) or each on a page of its own (*Separate pages*). *Keep Sources
 * as you left them* (#269), off by default, so the *Sources* list under each new track's insight
 * starts folded; under *Expandable boxes* only, so it is shown, but not offered, under *Separate
 * pages*, where each page opens fresh. *Lyrics links* (#728), on by default: whether Home shows a
 * *Lyrics* button for the playing track, with links to its lyrics pages or a Genius search; under it,
 * indented, *Search Genius when there is no link*, on by default, which can be moved only while
 * *Lyrics links* is on, as *Keep Sources as you left them* only under *Expandable boxes*. *Logo intro
 * at start* (#383), from the next time the app is opened. *Theme*, *Font* and *Layout* show their
 * choices as button groups (#637), one tap to choose. Last, *Restore dismissed messages* (#759): its
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
    val boxes = settings.layout == Layout.EXPANDABLE_BOXES
    SwitchRow(
        title = stringResource(R.string.settings_keep_sources),
        summary = stringResource(R.string.settings_keep_sources_summary),
        checked = settings.keepSourcesUnfolded,
        onChange = if (boxes) ({ on -> update { it.copy(keepSourcesUnfolded = on) } }) else null,
    )
    LyricsRows(settings, update)
    SwitchRow(
        title = stringResource(R.string.settings_intro),
        summary = stringResource(R.string.settings_intro_summary),
        checked = settings.introAtStart,
    ) { on -> update { it.copy(introAtStart = on) } }
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
    SwitchRow(
        title = stringResource(R.string.settings_lyrics_search),
        summary = null,
        checked = settings.lyricsSearch,
        indent = true,
        onChange = if (settings.lyricsLinks) ({ on -> update { it.copy(lyricsSearch = on) } }) else null,
    )
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

/** *Layout*'s choices. */
@Composable
internal fun layouts(): List<Pair<Layout, String>> =
    listOf(
        Layout.EXPANDABLE_BOXES to stringResource(R.string.settings_layout_boxes),
        Layout.SEPARATE_PAGES to stringResource(R.string.settings_layout_pages),
    )
