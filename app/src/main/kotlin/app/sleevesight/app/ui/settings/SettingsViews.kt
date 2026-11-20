@file:Suppress("TooManyFunctions") // The views' declaration, and the parts of the screen they add.

package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.theme.ChoiceButtonGroup
import app.sleevesight.app.ui.theme.NamedLinkText
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SettingsView
import app.sleevesight.core.settings.SettingsView.ADVANCED
import app.sleevesight.core.settings.SettingsView.ALL
import app.sleevesight.core.settings.SettingsView.BASIC

// The settings' views (#1447): which options each one shows, the switch between them under the
// title, and how an option outside the view is shown when asked for.

/**
 * A setting as the views know it: [view], the smallest view that shows it (each view shows what the
 * ones before it show, [SettingsView.includes]), and whether it is [changed] from its default.
 */
internal class ViewedRow(
    val view: SettingsView,
    val changed: Boolean,
)

/** Each section's settings as the views know them, by the title its row shows. */
internal typealias SettingViews = Map<SettingsSection, Map<String, ViewedRow>>

/**
 * The views each setting of the settings screen is in (#1447), the one declaration of them: each
 * row by the title it shows, as the search knows it ([settingsIndex]), with the smallest view that
 * shows it, and, for a setting with a value, the value [changed][ViewedRow.changed] from [defaults]
 * in [settings] says. [players] are the players listed under *Players*, by name. *Developer* is in
 * none: it shows in full in every view while developer mode is on. A row added to the settings
 * without a view here fails `SettingsViewsTest`; one left out would show in every view.
 */
@Suppress("LongMethod") // One line per setting: the declaration.
@Composable
internal fun settingViews(
    settings: Settings,
    defaults: Settings,
    players: List<String>,
): SettingViews {
    val views = LinkedHashMap<SettingsSection, MutableMap<String, ViewedRow>>()

    // [title] under [section] in [view], changed when [value] differs from its default.
    fun row(
        section: SettingsSection,
        title: String,
        view: SettingsView,
        value: ((Settings) -> Any?)? = null,
    ) {
        val changed = value != null && value(settings) != value(defaults)
        views.getOrPut(section) { LinkedHashMap() }[title] = ViewedRow(view, changed)
    }

    val playersSection = SettingsSection.PLAYERS
    players.forEach { row(playersSection, it, BASIC) }
    row(playersSection, stringResource(R.string.settings_players_missing_title), BASIC)
    row(playersSection, stringResource(R.string.settings_players_on_home), ADVANCED) { it.onlyEnabledPlayersOnHome }
    row(playersSection, stringResource(R.string.settings_player_logo), ADVANCED) { it.showPlayerLogo }
    row(playersSection, stringResource(R.string.settings_symbol_opens), ADVANCED) { it.symbolOpens }

    val sources = SettingsSection.SOURCES
    for ((name, on) in SOURCE_SWITCHES) row(sources, stringResource(name), SOURCE_VIEWS.getValue(name)) { on(it) }
    row(sources, stringResource(R.string.settings_musicbrainz_wait), ALL) { it.musicBrainzWait }
    row(sources, stringResource(R.string.settings_track_language), ADVANCED) { it.trackLanguageSearch }

    val ai = SettingsSection.AI_SERVICES
    for (provider in OFFERED_PROVIDERS) {
        val name = stringResource(providerName(provider))
        row(ai, name, BASIC)
        row(ai, stringResource(R.string.settings_ai_model_of, name), ADVANCED) { it.model(provider) }
        if (efforts(provider) != null) {
            row(ai, stringResource(R.string.settings_ai_effort_of, name), ALL) { it.effort(provider) }
        }
    }
    row(ai, stringResource(R.string.settings_ai_retry), ALL) { it.aiRetry }

    val secrets = SettingsSection.SECRETS
    row(secrets, stringResource(R.string.settings_scan_keys), BASIC)
    row(secrets, stringResource(R.string.settings_remove_all_keys), BASIC)
    for (id in SHOWN_KEYS) row(secrets, stringResource(keyName(id)), BASIC)
    row(secrets, stringResource(R.string.settings_show_phone_name), ALL) { it.showPhoneName }

    val genres = SettingsSection.GENRES
    row(genres, stringResource(R.string.settings_show_genres), BASIC)
    for (source in labelSourcesByName()) {
        row(genres, stringResource(labelSourceName(source)), ADVANCED) { source in it.labelSources }
    }

    val insights = SettingsSection.INSIGHTS
    row(insights, stringResource(R.string.settings_write_insights), BASIC)
    row(insights, stringResource(R.string.settings_prepare_next), ADVANCED) { it.prepareNextTrack }
    row(insights, stringResource(R.string.settings_background), ADVANCED) { it.lookUpInBackground }
    row(insights, stringResource(R.string.settings_write_automatically), BASIC)
    row(insights, stringResource(R.string.settings_crossfade), ADVANCED) { it.crossfade }
    row(insights, stringResource(R.string.settings_pause_auto_write), ADVANCED) { it.pauseAutoWriteAfter }
    row(insights, stringResource(R.string.settings_artist_facts_once), ALL) { it.artistFactsOncePerSession }
    row(insights, stringResource(R.string.settings_ai_web_search), ADVANCED) { it.aiWebSearch }
    row(insights, stringResource(R.string.settings_search_with_ai), BASIC)
    row(insights, stringResource(R.string.settings_read_aloud_voice), ADVANCED) { it.readAloudVoice }
    row(insights, stringResource(R.string.settings_read_aloud_music), ADVANCED) { it.readAloudMusic }
    row(insights, stringResource(R.string.settings_read_track_line), ALL) { it.readTrackLineFirst }
    row(insights, stringResource(R.string.settings_read_new_insights), ADVANCED) { it.readNewInsightsAloud }

    val history = SettingsSection.HISTORY
    row(history, stringResource(R.string.settings_keep_history), BASIC)
    row(history, stringResource(R.string.settings_clear_history), BASIC)
    row(history, stringResource(R.string.settings_look_up_again), ALL) { it.lookUpAfterClear }
    row(history, stringResource(R.string.settings_session_gap), ALL) { it.sessionGap }
    for ((source, name) in SHOWN_LIFETIMES) row(history, stringResource(name), ALL) { it.cacheLifetimes[source] }
    row(history, stringResource(R.string.settings_keep_artists), ADVANCED) { it.keepArtistResults }

    val appearance = SettingsSection.APPEARANCE
    row(appearance, stringResource(R.string.settings_theme), BASIC)
    row(appearance, stringResource(R.string.settings_theme_button), ADVANCED) { it.themeButton }
    row(appearance, stringResource(R.string.settings_font), BASIC)
    row(appearance, stringResource(R.string.settings_layout), BASIC)
    row(appearance, stringResource(R.string.settings_one_panel), ADVANCED) { it.onePanel }
    row(appearance, stringResource(R.string.settings_sources_on_new_track), ADVANCED) { it.sourcesOnNewTrack }
    row(appearance, stringResource(R.string.settings_items_on_new_track), ADVANCED) { it.itemsOnNewTrack }
    row(appearance, stringResource(R.string.settings_fold_descriptions), ALL) { it.foldDescriptions }
    row(appearance, stringResource(R.string.settings_descriptions_on_return), ALL) { it.descriptionsOnReturn }
    row(appearance, stringResource(R.string.settings_lyrics_links), ADVANCED) { it.lyricsLinks }
    row(appearance, stringResource(R.string.settings_lyrics_search), ALL) { it.lyricsSearch }
    row(appearance, stringResource(R.string.settings_intro), ADVANCED) { it.introAtStart }
    row(appearance, stringResource(R.string.settings_spin_logo), ADVANCED) { it.spinLogo }
    row(appearance, stringResource(R.string.settings_logo_speed), ALL) { it.logoSpeed }
    row(appearance, stringResource(R.string.settings_restore_dismissed), ADVANCED)

    val tools = SettingsSection.SAVE_AND_RESET
    row(tools, stringResource(R.string.settings_reset), BASIC)
    row(tools, stringResource(R.string.settings_save_file), BASIC)
    row(tools, stringResource(R.string.settings_load_file), BASIC)

    val about = SettingsSection.ABOUT
    row(about, stringResource(R.string.settings_version), BASIC)
    row(about, stringResource(R.string.credits_title), BASIC)
    row(about, stringResource(R.string.settings_what_leaves), BASIC)
    row(about, stringResource(R.string.settings_docs), ADVANCED)
    row(about, stringResource(R.string.settings_privacy), BASIC)
    row(about, stringResource(R.string.settings_source_code), ADVANCED)
    row(about, stringResource(R.string.settings_licence), ADVANCED)
    return views
}

/** The view of each of *Sources*' rows, by its title ([SOURCE_SWITCHES]). */
private val SOURCE_VIEWS: Map<Int, SettingsView> =
    mapOf(
        R.string.settings_source_musicbrainz to BASIC,
        R.string.settings_source_wikipedia to BASIC,
        R.string.settings_source_lastfm to BASIC,
        R.string.settings_engine_exa to BASIC,
        R.string.settings_source_lrclib to ADVANCED,
        R.string.settings_source_model to ADVANCED,
    )

/** [view]'s name, as its button names it: *Basic*, *Advanced*, *All*. */
@StringRes
internal fun viewName(view: SettingsView): Int =
    when (view) {
        BASIC -> R.string.settings_view_basic
        ADVANCED -> R.string.settings_view_advanced
        ALL -> R.string.settings_view_all
    }

/**
 * A section's rows as the view has them (#1447), for the rows drawn in it ([LocalRowView]): those
 * in [view] by [rows] (a row not in [rows] is in every view), and those [shown] though outside it,
 * by title, which carry the mark *Not in …* ([NotInViewMark]), [view]'s name in it.
 */
internal class RowView(
    private val view: SettingsView,
    private val rows: Map<String, ViewedRow>,
    private val shown: Set<String>,
) {
    /** Whether the row titled [title] is in the view. */
    fun inView(title: String): Boolean = rows[title]?.let { view.includes(it.view) } ?: true

    /** Whether the row titled [title] shows: in the view, or shown though outside it. */
    fun shows(title: String): Boolean = inView(title) || title in shown

    /** Whether the row titled [title] shows though outside the view, marked so. */
    fun marked(title: String): Boolean = !inView(title) && title in shown

    /** The view's name. */
    @StringRes
    val name: Int = viewName(view)
}

/**
 * What [view] shows of the settings (#1447), by [views]: in each section, the rows in the view, and
 * those [shown] though outside it, marked, each by its section and title: the hidden changes a
 * *Show* showed and the rows a link landed on. *Developer* has every row in every view. [index] has
 * the rows each section draws now, which alone count among its hidden changes.
 */
internal class ViewShown(
    val view: SettingsView,
    private val views: SettingViews,
    private val index: List<IndexedSection>,
    private val shown: Set<Pair<SettingsSection, String>> = emptySet(),
) {
    private fun rows(section: SettingsSection): Map<String, ViewedRow> = views[section].orEmpty()

    private fun inView(
        section: SettingsSection,
        title: String,
    ): Boolean = section == SettingsSection.DEVELOPER || rows(section)[title]?.let { view.includes(it.view) } ?: true

    private fun drawn(section: SettingsSection): List<String> =
        index
            .firstOrNull { it.section == section }
            ?.rows
            .orEmpty()
            .map { it.title }

    /**
     * [section]'s settings that differ from their defaults, not in the view and not shown: those its
     * line says are hidden, and its *Show* shows.
     */
    fun hiddenChanges(section: SettingsSection): List<String> {
        val outside = shownOutside(section)
        return drawn(section).filter { !inView(section, it) && it !in outside && rows(section)[it]?.changed == true }
    }

    // The rows of [section] shown though outside the view, but for a search's.
    private fun shownOutside(section: SettingsSection): Set<String> =
        shown.filter { it.first == section }.map { it.second }.toSet()

    /** [section]'s rows as the view has them, [more] (a search's) shown too; null for *Developer*. */
    fun rowView(
        section: SettingsSection,
        more: Set<String> = emptySet(),
    ): RowView? =
        if (section == SettingsSection.DEVELOPER) null else RowView(view, rows(section), shownOutside(section) + more)

    /**
     * What a search that [found] these shows in the view: the sections with a match in it, or a
     * whole one found by its name; and the matches outside it ([ViewSearch.outside]), all of them shown,
     * marked, with [showMore] (*Show them*).
     */
    fun search(
        found: Map<SettingsSection, SectionMatch>,
        showMore: Boolean,
    ): ViewSearch {
        val sections = LinkedHashMap<SettingsSection, SectionMatch>()
        val outside = LinkedHashMap<SettingsSection, Set<String>>()
        for ((section, match) in found) {
            val titles = match.rows ?: drawn(section).toSet()
            val shown = shownOutside(section)
            val out = titles.filter { !inView(section, it) && it !in shown }.toSet()
            if (out.isNotEmpty()) outside[section] = out
            if (showMore || match.rows == null || titles.size > out.size) sections[section] = match
        }
        // In *Advanced*, when every match outside *Basic* is; else in *All*.
        val home =
            outside.flatMap { (section, titles) -> titles.mapNotNull { rows(section)[it]?.view } }.let { homes ->
                if (homes.isNotEmpty() && homes.all { it == ADVANCED }) ADVANCED else ALL
            }
        return ViewSearch(sections, outside, home)
    }
}

/**
 * What a search shows in a view (#1447): the [sections] it shows, the matches [outside] the view in
 * each, and the smallest view that has them all ([home]).
 */
internal class ViewSearch(
    val sections: Map<SettingsSection, SectionMatch>,
    val outside: Map<SettingsSection, Set<String>>,
    val home: SettingsView,
) {
    /** How many matches are outside the view. */
    val more: Int get() = outside.values.sumOf { it.size }
}

/**
 * What a visit to the settings shows outside the view (#1447), until the view changes: the [rows],
 * each by its section and title, a section's hidden changes' *Show* showed ([reveal]) and a link
 * landed on ([land]). Kept by title, not by whether a row is still changed, so one set back to its
 * default stays in sight while it is being changed. Saved with the screen, so a rotation keeps them;
 * leaving for a page the settings open clears them ([clear]).
 */
@Stable
internal class ViewReveals(
    rows: Set<Pair<SettingsSection, String>> = emptySet(),
) {
    var rows by mutableStateOf(rows)
        private set

    /** Shows [section]'s rows [titles], its hidden changes. */
    fun reveal(
        section: SettingsSection,
        titles: List<String>,
    ) {
        rows = rows + titles.map { section to it }
    }

    /**
     * Shows nothing outside the view any more: the settings are left for a page they open (*Credits*,
     * *Diagnostics*, *What leaves the device*), which coming back from does not undo. A rotation
     * keeps them.
     */
    fun clear() {
        rows = emptySet()
    }

    /** Shows [section]'s row [title], which a link landed on. */
    fun land(
        section: SettingsSection,
        title: String,
    ) {
        rows = rows + (section to title)
    }

    /**
     * What [view] shows by [views], [index] the rows drawn, with [landing], a link's target landing
     * now, shown from as it lands, before [land] keeps it.
     */
    @Composable
    fun shown(
        view: SettingsView,
        views: SettingViews,
        index: List<IndexedSection>,
        landing: SettingsTarget?,
    ): ViewShown {
        val title = landing?.let { landedTitle(it) }
        LaunchedEffect(landing, title) {
            if (landing != null && title != null) land(landing.section, title)
        }
        val now = listOfNotNull(title?.let { landing.section to it })
        return ViewShown(view, views, index, rows + now)
    }

    companion object {
        /** Saved as a list: each row by its section's name and its title. */
        val Saver: Saver<ViewReveals, ArrayList<String>> =
            Saver(
                save = { reveals ->
                    ArrayList(reveals.rows.map { (section, title) -> "${section.name}$SEPARATOR$title" })
                },
                restore = { saved ->
                    ViewReveals(
                        saved
                            .mapNotNull { entry ->
                                val (name, title) = entry.split(SEPARATOR, limit = 2) + ""
                                SettingsSection.entries.firstOrNull { it.name == name }?.let { it to title }
                            }.toSet(),
                    )
                },
            )

        /** What parts a row's section and its title: the unit separator, never in a title. */
        private const val SEPARATOR = '\u001F'
    }
}

/**
 * What this visit to the settings shows outside [view] (#1447), until the view changes or the
 * settings are left: a section's hidden changes once *Show* is tapped, and a row a link lands on.
 */
@Composable
internal fun rememberViewReveals(view: SettingsView): ViewReveals =
    rememberSaveable(view, saver = ViewReveals.Saver) { ViewReveals() }

/**
 * At the top of [section]'s panel, its changed settings outside the view not shown yet, while any
 * are ([HiddenChangesLine]); [onShow] is told them, to show.
 */
@Composable
internal fun HiddenChanges(
    byView: ViewShown,
    section: SettingsSection,
    onShow: (List<String>) -> Unit,
) {
    val hidden = byView.hiddenChanges(section)
    if (hidden.isNotEmpty()) HiddenChangesLine(hidden.size) { onShow(hidden) }
}

/** The view, for the rows of the section drawn; null for none, as under *Developer*. */
internal val LocalRowView = staticCompositionLocalOf<RowView?> { null }

/**
 * Whether a line over the rows [over] (a [Hint], a [HeadingRow]) shows: while a search shows only
 * some rows, or a view leaves some out, only while one of [over] shows; with none, it is not a
 * setting of its own, and such a search leaves it out, while a view leaves it in.
 */
@Composable
internal fun overShown(over: List<String>): Boolean {
    val search = LocalRowSearch.current?.takeIf { it.filtering }
    val view = LocalRowView.current?.takeIf { over.isNotEmpty() }
    if (search == null && view == null) return true
    return over.any { (search?.shows(it) ?: true) && (view?.shows(it) ?: true) }
}

/**
 * The views to choose from (#1447), a button group under the title, above the search, with no label:
 * a tap on one shows the settings it has; it changes no setting's value. A screen reader reads each
 * button with *Settings view*.
 */
@Composable
internal fun ViewSwitch(
    view: SettingsView,
    onSelect: (SettingsView) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChoiceButtonGroup(
        SettingsView.entries.map { it to stringResource(viewName(it)) },
        view,
        onSelect,
        modifier.testTag(VIEW_SWITCH_TAG),
        name = stringResource(R.string.settings_view_name),
    )
}

/**
 * At the top of a section's panel, [count] settings of it that differ from their defaults but are
 * not in the view: *1 changed setting hidden. Show*; [onShow] shows them, marked.
 */
@Composable
internal fun HiddenChangesLine(
    count: Int,
    onShow: () -> Unit,
) {
    val line = pluralStringResource(R.plurals.settings_hidden_changes, count, count)
    val show = stringResource(R.string.settings_hidden_changes_show)
    Box(Modifier.padding(vertical = 4.dp)) {
        NamedLinkText(
            listOf(TextPart.Words("$line "), TextPart.Action(show, HIDDEN_SHOW_TAG, onShow)),
            show,
            MaterialTheme.typography.bodyMedium,
            HIDDEN_LINE_TAG,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * After a search's matches, [count] more outside the view, all of them in [view]: *2 more in All.
 * Show them*; [onShow] adds them, marked. The search's announcement says it ([SearchAnnouncement]).
 */
@Composable
internal fun SearchMoreLine(
    count: Int,
    view: SettingsView,
    onShow: () -> Unit,
) {
    val show = stringResource(R.string.settings_search_more_show)
    Box(Modifier.padding(top = 12.dp)) {
        NamedLinkText(
            listOf(
                TextPart.Words(searchMoreText(count, view) + " "),
                TextPart.Action(show, SEARCH_MORE_SHOW_TAG, onShow),
            ),
            show,
            MaterialTheme.typography.bodyMedium,
            SEARCH_MORE_TAG,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** *2 more in All.* */
@Composable
internal fun searchMoreText(
    count: Int,
    view: SettingsView,
): String = pluralStringResource(R.plurals.settings_search_more, count, count, stringResource(viewName(view)))

/** Under the title of a row shown though outside the view ([RowView.marked]): *Not in Basic*. */
@Composable
internal fun NotInViewMark(title: String) {
    val view = LocalRowView.current ?: return
    if (!view.marked(title)) return
    Text(
        stringResource(R.string.settings_not_in_view, stringResource(view.name)),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .padding(top = 2.dp, bottom = 2.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp)
                .testTag(NOT_IN_VIEW_TAG),
    )
}

/**
 * The title of the row [target] lands on where a view may leave it out (#1447), so a link to it
 * shows it, marked; null for a section, or a row every view has.
 */
@Composable
internal fun landedTitle(target: SettingsTarget): String? =
    when (target) {
        SettingsTarget.sessionGap -> stringResource(R.string.settings_session_gap)
        SettingsTarget.aiWebSearch -> stringResource(R.string.settings_ai_web_search)
        SettingsTarget.lyricsSearch -> stringResource(R.string.settings_lyrics_search)
        else ->
            OFFERED_PROVIDERS.firstOrNull { SettingsTarget.model(it) == target }?.let {
                stringResource(R.string.settings_ai_model_of, stringResource(providerName(it)))
            }
    }

/** The test tags of the view switch, the hidden changes' line and its *Show*, and the mark *Not in …*. */
internal const val VIEW_SWITCH_TAG = "settings_view_switch"
internal const val HIDDEN_LINE_TAG = "settings_hidden_changes"
internal const val HIDDEN_SHOW_TAG = "settings_hidden_changes_show"
internal const val NOT_IN_VIEW_TAG = "settings_not_in_view"

/** The test tags of the search's line of matches outside the view, and its *Show them*. */
internal const val SEARCH_MORE_TAG = "settings_search_more"
internal const val SEARCH_MORE_SHOW_TAG = "settings_search_more_show"
