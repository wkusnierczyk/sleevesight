package app.sleevesight.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.core.settings.PrepareNextTrack
import app.sleevesight.core.settings.Settings

// The lines of *Reset settings?* (#960): each setting a reset changes, by its title in the settings,
// as its value now and its default.

/**
 * One or more settings as *Reset settings?* lists them: [fields] are their properties in [Settings];
 * [lines] gives a line for each that differs between the settings now and after the reset, as
 * *Title: value now → default*, a model named by the function given, as its row names it.
 * [developer] for one under *Developer*, listed only while developer mode is on.
 */
internal class ResetLine(
    val fields: List<String>,
    val developer: Boolean = false,
    val lines: @Composable (now: Settings, reset: Settings, modelName: (String) -> String) -> List<String>,
)

/**
 * The settings no line lists, which a reset still puts back: which descriptions are unfolded (the
 * screen's folds, no setting of their own), and two with no row in the settings.
 */
internal val UNLISTED_FIELDS: Set<String> = setOf("unfoldedDescriptions", "artistCacheLifetime", "cacheSizeLimit")

/** A line for [title] if [now] and [reset] differ, each value as [label] has it. */
@Composable
private fun <T> line(
    title: String,
    now: T,
    reset: T,
    label: @Composable (T) -> String,
): List<String> =
    if (now == reset) {
        emptyList()
    } else {
        listOf(stringResource(R.string.settings_reset_line, title, label(now), label(reset)))
    }

/** *On* or *Off*, as a switch is. */
@Composable
private fun onOff(on: Boolean): String =
    stringResource(if (on) R.string.settings_reset_on else R.string.settings_reset_off)

/** A switch, [field], titled [title]. */
private fun switch(
    field: String,
    @StringRes title: Int,
    of: (Settings) -> Boolean,
): ResetLine =
    ResetLine(listOf(field)) { now, reset, _ -> line(stringResource(title), of(now), of(reset)) { onOff(it) } }

/** A setting, [field], titled [title], each value as [label] has it. */
private fun <T> valued(
    field: String,
    @StringRes title: Int,
    of: (Settings) -> T,
    label: @Composable (T) -> String,
): ResetLine = ResetLine(listOf(field)) { now, reset, _ -> line(stringResource(title), of(now), of(reset), label) }

/** A choice, [field], titled [title], each value by its label among [choices]. */
private fun <T> choice(
    field: String,
    @StringRes title: Int,
    of: (Settings) -> T,
    choices: @Composable () -> List<Pair<T, String>>,
): ResetLine =
    valued(field, title, of) { value -> choices().firstOrNull { it.first == value }?.second ?: value.toString() }

/** [title], with the name of the section it is in, where another setting's title is the same. */
@Composable
private fun inSection(
    @StringRes title: Int,
    @StringRes section: Int,
): String = stringResource(R.string.settings_reset_in_section, stringResource(title), stringResource(section))

/**
 * Every setting a reset can change but those [UNLISTED_FIELDS] names, in the order the settings show
 * them; a setting added to [Settings] is listed here, or the test of this list fails.
 */
internal val RESET_LINES: List<ResetLine> =
    listOf(
        // Players
        switch("onlyEnabledPlayersOnHome", R.string.settings_players_on_home) { it.onlyEnabledPlayersOnHome },
        // Sources: a row per source, a web search engine's on while web search is on with it.
        ResetLine(listOf("enabledSources", "webEngines")) { now, reset, _ ->
            SOURCE_SWITCHES.flatMap { (name, on) -> line(stringResource(name), on(now), on(reset)) { onOff(it) } }
        },
        valued("musicBrainzWait", R.string.settings_musicbrainz_wait, { it.musicBrainzWait }) {
            stopLabel(it, stringResource(R.string.settings_musicbrainz_wait_none))
        },
        choice("trackLanguageSearch", R.string.settings_track_language, { it.trackLanguageSearch }) {
            TRACK_LANGUAGE_CHOICES.map { (value, label, _) -> value to stringResource(label) }
        },
        // AI services: each service's model and reasoning effort.
        ResetLine(listOf("aiModels")) { now, reset, modelName ->
            OFFERED_PROVIDERS.flatMap { provider ->
                val title = stringResource(R.string.settings_ai_model_of, stringResource(providerName(provider)))
                if (now.model(provider) == reset.model(provider)) {
                    emptyList()
                } else {
                    val from = modelValue(provider, now, modelName)
                    val to = modelValue(provider, reset, modelName)
                    listOf(stringResource(R.string.settings_reset_line, title, from, to))
                }
            }
        },
        ResetLine(listOf("aiEfforts")) { now, reset, _ ->
            OFFERED_PROVIDERS.flatMap { provider ->
                val options = efforts(provider).orEmpty()
                val title = stringResource(R.string.settings_ai_effort_of, stringResource(providerName(provider)))
                line(title, now.effort(provider), reset.effort(provider)) { effort ->
                    options.firstOrNull { it.first == effort }?.second ?: effort.toString()
                }
            }
        },
        valued("aiRetry", R.string.settings_ai_retry, { it.aiRetry }) {
            stopLabel(it, stringResource(R.string.settings_ai_retry_none))
        },
        // Secrets
        switch("showPhoneName", R.string.settings_show_phone_name) { it.showPhoneName },
        // Genres
        switch("showGenres", R.string.settings_show_genres) { it.showGenres },
        ResetLine(listOf("labelSources")) { now, reset, _ ->
            labelSourcesByName().flatMap { source ->
                val title = inSection(labelSourceName(source), R.string.settings_section_labels)
                line(title, source in now.labelSources, source in reset.labelSources) { onOff(it) }
            }
        },
        // Insights
        switch("writeInsights", R.string.settings_write_insights) { it.writeInsights },
        switch("prepareNextTrack", R.string.settings_prepare_next) { it.prepareNextTrack == PrepareNextTrack.EVIDENCE },
        switch("lookUpInBackground", R.string.settings_background) { it.lookUpInBackground },
        choice("writeAutomatically", R.string.settings_write_automatically, { it.writeAutomatically }) {
            WRITING_MODES.map { it.mode to stringResource(it.short) }
        },
        valued("crossfade", R.string.settings_crossfade, { it.crossfade }) { crossfadeLabel(it) },
        valued("pauseAutoWriteAfter", R.string.settings_pause_auto_write, { it.pauseAutoWriteAfter }) { count ->
            count?.toString() ?: onOff(false)
        },
        switch("artistFactsOncePerSession", R.string.settings_artist_facts_once) { it.artistFactsOncePerSession },
        choice("aiWebSearch", R.string.settings_ai_web_search, { it.aiWebSearch }) { aiWebSearchChoices() },
        switch("searchWithAi", R.string.settings_search_with_ai) { it.searchWithAi },
        choice("readAloudMusic", R.string.settings_read_aloud_music, { it.readAloudMusic }) { readAloudMusics() },
        switch("readTrackLineFirst", R.string.settings_read_track_line) { it.readTrackLineFirst },
        switch("readNewInsightsAloud", R.string.settings_read_new_insights) { it.readNewInsightsAloud },
        // History
        valued("historyLifetime", R.string.settings_keep_history, { it.historyLifetime }) { lifetimeLabel(it) },
        choice("lookUpAfterClear", R.string.settings_look_up_again, { it.lookUpAfterClear }) { lookUpAgainChoices() },
        valued("sessionGap", R.string.settings_session_gap, { it.sessionGap }) { durationLabel(it) },
        ResetLine(listOf("cacheLifetimes")) { now, reset, _ ->
            SHOWN_LIFETIMES.flatMap { (source, name) ->
                val from = now.cacheLifetimes[source]
                val to = reset.cacheLifetimes[source]
                if (from == null || to == null) {
                    emptyList()
                } else {
                    line(inSection(name, R.string.settings_section_history), from, to) { durationLabel(it) }
                }
            }
        },
        switch("keepArtistResults", R.string.settings_keep_artists) { it.keepArtistResults },
        // Appearance
        choice("theme", R.string.settings_theme, { it.theme }) { themes() },
        switch("themeButton", R.string.settings_theme_button) { it.themeButton },
        choice("font", R.string.settings_font, { it.font }) { fonts() },
        choice("layout", R.string.settings_layout, { it.layout }) { layouts() },
        switch("onePanel", R.string.settings_one_panel) { it.onePanel },
        choice("sourcesOnNewTrack", R.string.settings_sources_on_new_track, { it.sourcesOnNewTrack }) {
            newTrackFolds()
        },
        choice("itemsOnNewTrack", R.string.settings_items_on_new_track, { it.itemsOnNewTrack }) { newTrackFolds() },
        switch("foldDescriptions", R.string.settings_fold_descriptions) { it.foldDescriptions },
        choice("descriptionsOnReturn", R.string.settings_descriptions_on_return, { it.descriptionsOnReturn }) {
            descriptionsOnReturn()
        },
        switch("lyricsLinks", R.string.settings_lyrics_links) { it.lyricsLinks },
        switch("lyricsSearch", R.string.settings_lyrics_search) { it.lyricsSearch },
        switch("introAtStart", R.string.settings_intro) { it.introAtStart },
        switch("spinLogo", R.string.settings_spin_logo) { it.spinLogo },
        choice("logoSpeed", R.string.settings_logo_speed, { it.logoSpeed }) { logoSpeeds() },
    ) +
        // Developer: listed only while developer mode is on.
        listOf(
            switch("showDiagnostics", R.string.settings_diagnostics) { it.showDiagnostics },
            switch("showGroundingDetails", R.string.settings_grounding_details) { it.showGroundingDetails },
            choice("reportButton", R.string.settings_report_button, { it.reportButton }) { reportButtons() },
            choice("linkStyle", R.string.settings_link_style, { it.linkStyle }) { linkStyles() },
            switch("markDeveloperItems", R.string.settings_mark_developer) { it.markDeveloperItems },
        ).map { ResetLine(it.fields, developer = true, lines = it.lines) }
