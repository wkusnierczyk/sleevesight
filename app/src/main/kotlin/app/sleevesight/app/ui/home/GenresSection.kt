package app.sleevesight.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.withAi
import app.sleevesight.app.ui.logo.Busy
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.theme.ChipFrame
import app.sleevesight.app.ui.theme.LinkedText
import app.sleevesight.app.ui.theme.PanelRule
import app.sleevesight.app.ui.theme.TextPart
import app.sleevesight.app.ui.theme.chipColours
import app.sleevesight.app.ui.theme.handoffTarget
import app.sleevesight.app.ui.theme.linkedParts
import app.sleevesight.app.ui.theme.rememberFocusHandoff
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.PlacedLabel
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.LaterLabels
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.settings.Settings

/**
 * *Genres* in the line under the album ([InPlaceLine], #772), beside *Dates*: its button, shown for
 * every track while *Show genres* is on in [settings] (with it off, #444, none, as no genres are
 * asked for; nor before the settings' first read), and its panel, which holds the genre labels
 * (#439, #558; `design/design.md`, *Genre labels*) of the track, its artists and its album
 * ([GenresContent]). [retries] asks a label source that could not be reached again, and
 * [openSettingsAt] opens the settings where the genre sources are switched on. Under *Separate
 * pages*, with [openPage], the button opens the genres' page instead ([GenresPage]), and a screen
 * reader names its action "open page", as *Sources*' (#447).
 */
@Suppress("LongParameterList") // What the panel shows, and each of its ways out.
@Composable
internal fun genresItem(
    track: TrackState?,
    settings: Settings?,
    notes: PanelNotes,
    retries: Retries,
    openSettingsAt: (SettingsTarget) -> Unit,
    openPage: (() -> Unit)? = null,
    search: PanelSearch = PanelSearch(),
): InPlaceItem? {
    if (settings?.showGenres != true) return null
    return InPlaceItem(
        id = InPlace.GENRES,
        name = stringResource(R.string.home_genres),
        action = stringResource(if (openPage != null) R.string.home_page_open else R.string.home_genres_show),
        tag = if (openPage != null) GENRES_PAGE_LINK_TAG else GENRES_CHIP_TAG,
        panelTag = GENRES_PANEL_TAG,
        page = openPage,
    ) { toClose -> GenresContent(track, settings, notes, retries::of, openSettingsAt, toClose, search = search) }
}

/**
 * What *Genres*' panel holds (#772), as [GenresShown] works it out from [playing] and [settings]: the
 * chips of every genre of the rows ([TrackGenres.chips], #875: the sources', in alphabetical order,
 * those of weak evidence marked; then those the AI's genre list added, #762, marked AI,
 * [GenreNotes.aiGenres]), each unfolding its genre under them and a
 * rule (#786, [GenresBox], [GenreBody]); under the chips, or in their place with none, why
 * there are no more: "Looking on Last.fm and MusicBrainz…" while the genres are gathered (the
 * sources still asked), a source that could not be reached with *Retry* beside it ([retry] of its
 * [SourceId], [RetryLine], which then hands the focus to the panel's *Close*, [toClose]), "None
 * found."; and with every genre source off, two lines, the second a link to where they are switched
 * on ([openSettingsAt]: *Settings → Sources*, or *Settings → Genres* when the sources are on and
 * their genre switches are not). With [everyGenre] (the genres' page, #772), no chips: every
 * genre of the chips, in their order, in a panel of its own titled with its name ([GenrePanel], with
 * no *Close*). With no genre and no source still asked (every genre source off, one not reached, none
 * found), *Search with AI* under the lines ([search], #773), whose genres show as chips that unfold
 * their genre as the others do ([AiGenres]), on the genres' page as well. A genre with no definition
 * from Wikipedia offers *Search with AI* for one, from [search] too (#786). Lyrics pages *Find the
 * lyrics* found for the track ([search]'s, #996) drop Instrumental from every row, as MusicBrainz's
 * lyrics links do ([TrackGenres.withLyricsFound]).
 */
@Suppress("LongParameterList") // What the panel shows, and each of its ways out.
@Composable
internal fun GenresContent(
    playing: TrackState?,
    settings: Settings,
    notes: PanelNotes = PanelNotes(),
    retry: (SourceId) -> (() -> Unit)? = { null },
    openSettingsAt: (SettingsTarget) -> Unit = {},
    toClose: () -> Unit = {},
    everyGenre: Boolean = false,
    search: PanelSearch = PanelSearch(),
) {
    // The track's genres with the AI's genre list (#762), where one is known: its genres join the
    // chips and the track's row, marked AI; an Instrumental it added weighed with LRCLIB's answer (#997).
    // The genres the artists' descriptions name (#1000), beside the tags, before the AI's list's.
    val described = withDescribed(playing, notes)
    val joined = described?.withAi(aiGenres(described, notes.notes))
    val weighed = withLibrary(described, joined, notes.notes)
    val track = withLyricsFound(weighed.track, search)
    val shown = GenresShown.of(track, settings)
    val style = MaterialTheme.typography.bodyLarge
    // The genre a tap opened, kept above the chips and the lines in their place (#772): while a run
    // asks again, the chips may go and come back, and the genre with them. Cleared on each new track;
    // kept by the genre's identity, so it outlasts labels that land later; dropped once the genres
    // are in and no row has it any more. Until a chip is tapped on the track ([tapped]), the panel
    // shows the genre it opens on (#875, [GenresBox]).
    val chosen = rememberSaveable(track?.normalized?.lookupKey) { mutableStateOf<String?>(null) }
    val tapped = rememberSaveable(track?.normalized?.lookupKey) { mutableStateOf(false) }
    val gone =
        track != null &&
            track.gathered &&
            track.genresAssembled &&
            chosen.value?.let { !track.genres.has(it) } == true
    SideEffect { if (gone) chosen.value = null }
    if (track != null && !track.genres.isEmpty) {
        if (everyGenre) {
            EveryGenre(track, notes, search, toClose)
        } else {
            GenresBox(track, notes, Choice(chosen, tapped), search)
        }
    }
    shown.off?.let { target ->
        Text(stringResource(R.string.home_genres_off), style = style)
        val linkText =
            if (target ==
                SettingsTarget.sources
            ) {
                R.string.home_settings_sources_link
            } else {
                R.string.home_settings_genres_link
            }
        val link = TextPart.Action(stringResource(linkText), target.linkTag) { openSettingsAt(target) }
        LinkedText(linkedParts(stringResource(R.string.home_genres_off_see), link), style = style)
    }
    if (shown.looking.isNotEmpty()) {
        Busy(stringResource(R.string.home_genres_looking, sourcesNamed(shown.looking)), style)
    }
    for (source in shown.unreachable) {
        val name = sourceName(source.source)
        RetryLine(
            stringResource(R.string.home_genre_source_unreachable, name),
            name,
            retry(source.source),
            afterRetry = toClose,
        )
    }
    LibraryRetry(shown.libraryUnreachable, weighed.retry, retry, toClose)
    if (shown.none) Text(stringResource(R.string.home_genres_none), style = style)
    GenresSearch(track, shown, search, notes)
}

// [sources] named together: "Last.fm", "Last.fm and MusicBrainz".
@Composable
private fun sourcesNamed(sources: List<LabelSource>): String {
    val names = sources.map { sourceName(it.source) }
    return names.drop(1).fold(names.first()) { all, next -> stringResource(R.string.home_sources_two, all, next) }
}

/**
 * What *Genres*' panel says besides its chips (#772), for a track and the settings: the label
 * sources still being asked while the genres are gathered, by name ([looking]); those that could
 * not be reached, each with *Retry* ([unreachable]); with every genre source off and no genre, where
 * its link leads ([off]); whether it says none were found ([none]); and whether LRCLIB, asked about
 * the track's Instrumental, could not be reached, with *Retry* ([libraryUnreachable], #997).
 */
internal data class GenresShown(
    val looking: List<LabelSource> = emptyList(),
    val unreachable: List<LabelSource> = emptyList(),
    val off: SettingsTarget? = null,
    val none: Boolean = false,
    val libraryUnreachable: Boolean = false,
) {
    companion object {
        /**
         * What the panel says for [track] under [settings]. With no genre and every genre source off
         * ([labelsOff]), the link: to *Sources* when every label source is switched off there, else to
         * *Genres*, where their genre switches are ([Settings.givesLabels]). Until the genres are
         * assembled with the evidence ([TrackState.gathered] and [TrackState.genresAssembled]: the
         * sources still asked, the last one's result being kept, or before the pipeline reaches the
         * track), the label sources on that are still pending, or, with none pending, all those on.
         * Then, the label sources on whose requests after the evidence are still asked
         * ([TrackState.laterLabels]: MusicBrainz's artist tags, Last.fm's track and album tags),
         * which may yet bring genres; and each whose lookup ended unreachable (or failed), or whose
         * later request failed. None found only when every label source on is done with nothing.
         */
        fun of(
            track: TrackState?,
            settings: Settings,
        ): GenresShown {
            val empty = track?.genres?.isEmpty ?: true
            if (empty && labelsOff(settings)) {
                val sourcesOff = LabelSource.entries.none { settings.isEnabled(it.source) }
                return GenresShown(off = if (sourcesOff) SettingsTarget.sources else SettingsTarget.genres)
            }
            val on = LabelSource.entries.filter(settings::givesLabels)
            return if (track == null || !track.gathered || !track.genresAssembled) {
                val states = track?.sources.orEmpty()
                GenresShown(looking = on.filter { states[it.source] is SourceState.Pending }.ifEmpty { on })
            } else {
                gathered(track, on, empty)
            }
        }

        // Once [track]'s genres are assembled: of the label sources [on], those whose later requests are
        // still asked, and those not reached; with none of either and no genre ([empty]), none found.
        private fun gathered(
            track: TrackState,
            on: List<LabelSource>,
            empty: Boolean,
        ): GenresShown {
            val later = track.laterLabels
            val looking = on.filter { later[it] == LaterLabels.ASKING }
            val unreachable = on.filter { unreachable(track.sources[it.source]) || later[it] == LaterLabels.FAILED }
            return GenresShown(
                looking,
                unreachable,
                none = empty && looking.isEmpty() && unreachable.isEmpty(),
                libraryUnreachable = track.libraryUnreachable,
            )
        }

        // Whether a source's lookup ended unreachable, or failed: *Retry* asks it again.
        private fun unreachable(state: SourceState?): Boolean =
            state is SourceState.Failed ||
                (state is SourceState.Done && state.result.outcome == Outcome.Unreachable)
    }
}

/** Whether every label source is off in [settings], as read. */
internal fun labelsOff(settings: Settings?): Boolean =
    settings != null && LabelSource.entries.none { settings.givesLabels(it) }

/**
 * The genre a tap on a chip chose in *Genres*' panel ([chosen], null when the tap folded it), and
 * whether a chip was tapped on the track ([tapped]): until one is, the panel shows the genre it
 * opens on (#875).
 */
internal class Choice(
    val chosen: MutableState<String?>,
    val tapped: MutableState<Boolean>,
)

/**
 * In *Genres*' panel (#772): the chips ([TrackGenres.chips], #875), and under them the genre open,
 * if any, unfolded under a rule, with no heading or *Close* of its own (#786, [GenreBody]). Until a
 * chip is tapped on the track ([choice]), the genre open is the one the panel opens on
 * ([TrackGenres.opening]: the first backed, else the first weak, never the AI's), its chip
 * selected, as after a tap: when the panel opens, when a new track's genres come while it is open,
 * and, until a tap, as the track's labels land, following the first backed genre. Then the chip
 * tapped: another chip replaces it, and a second tap on the same chip folds it (a screen reader
 * reads that chip as selected, its action "fold"); the chips stay. A tap leaves the focus on its
 * chip; the automatic choice moves no focus. A genre that goes or is replaced for another reason (a
 * new track, labels that no longer have it, or the automatic choice moving on) while the focus is in
 * it hands the focus to the chip of the genre tapped, wherever its chip now is ([FocusHandoff],
 * #629); with none tapped, or when it is gone, to the open genre's chip, else to the first chip.
 * *Retry* under a failed definition hands the focus to its genre's chip as it gives way to the
 * loading line. While a new track's genres are still being gathered there is no chip, and nothing to
 * hand the focus to. Its definition's *Search with AI* comes from [search].
 */
@Composable
private fun GenresBox(
    track: TrackState,
    notes: PanelNotes,
    choice: Choice,
    search: PanelSearch,
) {
    var chosen by choice.chosen
    var touched by choice.tapped
    val order = rememberGenreOrder()
    val labels = track.genres.chips(order)
    // [GenresContent]'s: a genre no row has (yet, or any more) shows no panel.
    val open =
        if (touched) {
            chosen?.takeIf { track.genres.has(it) }
        } else {
            track.genres
                .opening(order)
                ?.label
                ?.genre
                ?.identity
        }
    val handoff = rememberFocusHandoff()
    // A requester per chip shown, by its genre's identity, and the genre of the chip last tapped:
    // plain, read only by the moves. By genre, not by the chip's tag, which names its row: a genre
    // tapped on an artist's row keeps its chip when the track's own tag of it lands (#875).
    val chips = remember(track.normalized.lookupKey) { mutableMapOf<String, FocusRequester>() }
    val tapped = remember { arrayOfNulls<String>(1) }
    val chipOf = { genre: String -> chips.getOrPut(genre) { FocusRequester() } }
    // The chip tapped, if still shown; else the open genre's (the one the panel opened on, #875);
    // else the first, when both went with the old track or the labels.
    val back = {
        val genres = labels.map { it.label.genre.identity }
        (tapped[0]?.takeIf { it in genres } ?: open?.takeIf { it in genres } ?: genres.firstOrNull())?.let(chipOf)
    }
    // Whether the input focus is in the panel, as last told: plain, read as the panel goes.
    val inside = remember { BooleanArray(1) }
    val before = remember { arrayOfNulls<String>(1).also { it[0] = open } }
    // Gone or replaced by other than a tap on a chip while the focus was in it: back to the chip, or
    // a keyboard's focus would fall to the top of the screen. Read before the genre goes.
    val handBack = open != before[0] && before[0] != null && inside[0]
    SideEffect {
        before[0] = open
        // No panel, no focus in it: a panel that goes may not be told it lost the focus.
        if (open == null) inside[0] = false
        if (handBack) back()?.let(handoff::moveTo)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val target = { label: PlacedLabel -> Modifier.handoffTarget(handoff, chipOf(label.label.genre.identity)) }
        Chips(labels, open, chipModifier = target) { label ->
            val genre = label.label.genre.identity
            tapped[0] = genre
            // Unfolded, replaced or folded; the focus stays on the chip.
            chosen = if (genre == open) null else genre
            touched = true
        }
        // The pages the AI's genres came from, when from the web (#801), under the chips they marked.
        if (labels.any { it.label.aiAdded }) for (source in track.genres.aiSources) FromSite(source)
        open?.let {
            PanelRule()
            Box(Modifier.onFocusChanged { state -> inside[0] = state.hasFocus }) {
                GenreBody(
                    track,
                    it,
                    notes,
                    search,
                    // To the genre's own chip, the one open, tapped or not.
                    afterRetry = { handoff.moveTo(chipOf(it)) },
                    modifier = Modifier.testTag(GENRE_PANEL_TAG),
                )
            }
        }
    }
}

// Every genre of [track]'s chips, in their order, each in a panel of its own titled with its name,
// with no *Close* (the genres' page, #772): a genre several artists share, once. A failed definition's
// *Retry* hands the focus to [afterRetry] (the page's back arrow) as it goes.
@Composable
private fun EveryGenre(
    track: TrackState,
    notes: PanelNotes,
    search: PanelSearch,
    afterRetry: () -> Unit,
) {
    val order = rememberGenreOrder()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (label in track.genres.chips(order)) {
            // Keyed by genre: a genre landing earlier in the order moves the panels, not their content.
            key(label.label.genre.identity) {
                GenrePanel(track, label.label.genre.identity, notes, search, afterRetry)
            }
        }
    }
}

/**
 * [labels] as chips, in *Genres*' panel (#772), the one of the genre [open] marked, each under
 * [chipModifier]'s modifier for it (a focus requester); each chip a button that [tap]s it: to unfold
 * the genre under them, or, the one [open], to fold it ("fold", #786).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Chips(
    labels: List<PlacedLabel>,
    open: String?,
    chipModifier: (PlacedLabel) -> Modifier = { Modifier },
    tap: (PlacedLabel) -> Unit,
) {
    val showAction = stringResource(R.string.home_genre_show)
    val foldAction = stringResource(R.string.home_genre_fold)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        for (label in labels) {
            // Keyed by genre (#875): a genre landing earlier in the alphabetical order moves the chips,
            // and a keyboard's or a screen reader's focus stays with its genre's chip, not its place.
            key(label.label.genre.identity) {
                val selected = label.label.genre.identity == open
                GenreChip(label, selected, if (selected) foldAction else showAction, chipModifier(label)) { tap(label) }
            }
        }
    }
}

/**
 * A chip in *Genres*' panel (#439, #558, #772): a light fill in the text's colour; while [selected],
 * the text's colour, with the box's colour for the text. A button whose action a screen reader
 * names [action], and says is selected while its genre shows. [modifier] goes outside its
 * target (a focus requester). A genre the AI's genre list added (#762) carries the **AI** mark after
 * its name ([AiLabel]), and a screen reader reads it with the model's name.
 */
@Composable
private fun GenreChip(
    placed: PlacedLabel,
    selected: Boolean,
    action: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val (fill, content) = chipColours(selected)
    ChipFrame(
        fill,
        modifier = modifier.testTag(tagOf(GENRE_PREFIX, placed)),
        action =
            Modifier
                .clickable(role = Role.Button, onClickLabel = action, onClick = onClick)
                .semantics { this.selected = selected },
    ) {
        val label = placed.label.genre.label
        val model = placed.label.aiModel?.takeIf { placed.label.aiAdded }
        // A genre the artist's description names (#1000), read "Instrumental, from the artist’s description".
        val spoken = if (placed.label.describedIn != null) stringResource(R.string.genre_described, label) else label
        CompositionLocalProvider(LocalContentColor provides content) {
            if (placed.weak && model != null) {
                // The AI's Instrumental on a sung track (#996): weak, and still marked AI.
                WeakAiName(label, model, selected)
            } else if (placed.weak) {
                WeakName(label, selected, spoken)
            } else if (model == null) {
                Text(
                    label,
                    modifier = Modifier.semantics { if (spoken != label) contentDescription = spoken },
                    style = MaterialTheme.typography.labelLarge,
                )
            } else {
                // Added by the AI's genre list (#762): marked AI, read "Blues, from Claude Haiku 5.5".
                ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                    AiLabel(label, described = stringResource(R.string.genre_ai_added, label, model))
                }
            }
        }
    }
}

// Whether any row has [genre], of the tags or of a description (#1000).
private fun TrackGenres.has(genre: String): Boolean = everyLabel.any { it.genre.identity == genre }

/** The test tags of *Genres*' button and its panel (#772), the panel's heading and *Close* as [Panel] makes them. */
const val GENRES_CHIP_TAG: String = "genres-chip"
const val GENRES_PANEL_TAG: String = "genres-panel"
const val GENRES_PANEL_HEADING_TAG: String = "genres-panel:heading"
const val GENRES_PANEL_CLOSE_TAG: String = "genres-panel:close"

/** The test tag of *Genres*' button under *Separate pages* (#772), which opens the genres' page. */
const val GENRES_PAGE_LINK_TAG: String = "genres-page-link"

/**
 * The test tag of a chip in *Genres*' panel, before its level (`track`, `artist`,
 * `album`; `described` for a genre an artist's description names, #1000), a colon, for an artist's
 * the artist and a colon, and its identity: `genre:artist:The Beatles:rock`.
 */
const val GENRE_PREFIX: String = "genre:"

/** The test tag of a label in the panel's table, before its level, artist and identity, as [GENRE_PREFIX]. */
const val GENRE_CELL_PREFIX: String = "genre-cell:"

/** The test tag of [placed] after [prefix]: its level, its artist for an artist's, and its identity. */
internal fun tagOf(
    prefix: String,
    placed: PlacedLabel,
): String {
    val artist =
        placed.row.artist
            ?.let { "$it:" }
            .orEmpty()
    val level =
        if (placed.row.described) {
            "described"
        } else {
            placed.row.level.name
                .lowercase()
        }
    return prefix + level + ":" + artist + placed.label.genre.identity
}
