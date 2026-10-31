package app.sleevesight.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sleevesight.app.R
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.app.ui.theme.ChipFrame
import app.sleevesight.app.ui.theme.LinkedText
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
): InPlaceItem? {
    if (settings?.showGenres != true) return null
    return InPlaceItem(
        id = InPlace.GENRES,
        name = stringResource(R.string.home_genres),
        action = stringResource(if (openPage != null) R.string.home_page_open else R.string.home_genres_show),
        tag = if (openPage != null) GENRES_PAGE_LINK_TAG else GENRES_CHIP_TAG,
        panelTag = GENRES_PANEL_TAG,
        page = openPage,
    ) { toClose -> GenresContent(track, settings, notes, retries::of, openSettingsAt, toClose) }
}

/**
 * What *Genres*' panel holds (#772), as [GenresShown] works it out from [track] and [settings]: the
 * chips of the most specific level ([TrackGenres.folded]), each opening its genre in a box of its
 * own under them ([GenresBox], [GenrePanel]); under the chips, or in their place with none, why
 * there are no more: "Looking on Last.fm and MusicBrainz…" while the genres are gathered (the
 * sources still asked), a source that could not be reached with *Retry* beside it ([retry] of its
 * [SourceId], [RetryLine], which then hands the focus to the panel's *Close*, [toClose]), "None
 * found."; and with every genre source off, two lines, the second a link to where they are switched
 * on ([openSettingsAt]: *Settings → Sources*, or *Settings → Genres* when the sources are on and
 * their genre switches are not). With [everyGenre] (the genres' page, #772), no chips: every
 * genre of the chips, in their order, in a panel of its own ([GenrePanel], with no *Close*).
 */
@Suppress("LongParameterList") // What the panel shows, and each of its ways out.
@Composable
internal fun GenresContent(
    track: TrackState?,
    settings: Settings,
    notes: PanelNotes = PanelNotes(),
    retry: (SourceId) -> (() -> Unit)? = { null },
    openSettingsAt: (SettingsTarget) -> Unit = {},
    toClose: () -> Unit = {},
    everyGenre: Boolean = false,
) {
    val shown = GenresShown.of(track, settings)
    val style = MaterialTheme.typography.bodyLarge
    if (track != null && !track.genres.isEmpty) {
        if (everyGenre) EveryGenre(track, notes) else GenresBox(track, notes)
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
        Text(stringResource(R.string.home_genres_looking, sourcesNamed(shown.looking)), style = style)
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
    if (shown.none) Text(stringResource(R.string.home_genres_none), style = style)
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
 * its link leads ([off]); and whether it says none were found ([none]).
 */
internal data class GenresShown(
    val looking: List<LabelSource> = emptyList(),
    val unreachable: List<LabelSource> = emptyList(),
    val off: SettingsTarget? = null,
    val none: Boolean = false,
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
            return GenresShown(looking, unreachable, none = empty && looking.isEmpty() && unreachable.isEmpty())
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
 * In *Genres*' panel (#772): the chips, and under them the genre chosen, if any, in a box of its own
 * ([GenrePanel], on the surface colour, which sets it apart from the panel's fill): another chip
 * switches it, and a second tap on the same chip, or its *Close*, folds it; the chips stay. The
 * focus moves as *Sources*' does ([FocusHandoff], #629): to the panel's *Close* when a chip opens a
 * genre, and back to the chip tapped when *Close* closes it (a keyboard's, and a screen reader's;
 * nothing moves while merely touched). A panel that goes for another reason (a new track, or labels
 * that no longer have its genre) while the focus is in it hands the focus to that chip too, or, when
 * it is gone with the old track, to the first chip; while a new track's genres are still being
 * gathered there is no chip, and nothing to hand the focus to.
 */
@Composable
private fun GenresBox(
    track: TrackState,
    notes: PanelNotes,
) {
    // Closed on each new track. Kept by the genre's identity, so it outlasts labels that land later;
    // a genre no row has any more shows no panel.
    var chosen by rememberSaveable(track.normalized.lookupKey) { mutableStateOf<String?>(null) }
    val open = chosen?.takeIf { track.genres.has(it) }
    val labels = track.genres.folded
    val handoff = rememberFocusHandoff()
    val close = remember { FocusRequester() }
    // A requester per chip shown, by its tag, and the tag of the chip last tapped: plain, read only
    // by the moves.
    val chips = remember(track.normalized.lookupKey) { mutableMapOf<String, FocusRequester>() }
    val tapped = remember { arrayOfNulls<String>(1) }
    val chipOf = { tag: String -> chips.getOrPut(tag) { FocusRequester() } }
    // The chip tapped, or the first, when the one tapped went with the old track.
    val back = {
        val tags = labels.map { tagOf(GENRE_PREFIX, it) }
        (tapped[0]?.takeIf { it in tags } ?: tags.firstOrNull())?.let(chipOf)
    }
    // Whether the input focus is in the panel, as last told: plain, read as the panel goes.
    val inside = remember { BooleanArray(1) }
    val before = remember { BooleanArray(1).also { it[0] = open != null } }
    // Gone by other than *Close* while the focus was in it: back to the chip, or a keyboard's focus
    // would fall to the top of the screen. Read before the panel goes.
    val handBack = open == null && before[0] && inside[0]
    SideEffect {
        before[0] = open != null
        // No panel, no focus in it: a panel that goes may not be told it lost the focus.
        if (open == null) inside[0] = false
        if (handBack) back()?.let(handoff::moveTo)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val target = { label: PlacedLabel -> Modifier.handoffTarget(handoff, chipOf(tagOf(GENRE_PREFIX, label))) }
        Chips(labels, open, chipModifier = target) { label ->
            val genre = label.label.genre.identity
            tapped[0] = tagOf(GENRE_PREFIX, label)
            chosen = if (genre == open) null else genre
            // Opened, or switched to another genre: to *Close*. The same chip again closes the
            // panel, and the focus stays on the chip.
            if (genre != open) handoff.moveTo(close)
        }
        open?.let {
            Box(Modifier.onFocusChanged { state -> inside[0] = state.hasFocus }) {
                GenrePanel(
                    track,
                    it,
                    onClose = {
                        chosen = null
                        back()?.let(handoff::moveTo)
                    },
                    notes = notes,
                    closeModifier = Modifier.handoffTarget(handoff, close),
                    fill = MaterialTheme.colorScheme.surface,
                )
            }
        }
    }
}

// Every genre of [track]'s chips, in their order, each in a panel of its own, with no *Close* (the
// genres' page, #772): a genre several artists share, once.
@Composable
private fun EveryGenre(
    track: TrackState,
    notes: PanelNotes,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (label in track.genres.folded.distinctBy { it.label.genre.identity }) {
            GenrePanel(track, label.label.genre.identity, notes = notes)
        }
    }
}

/**
 * [labels] as chips, in *Genres*' panel (#772), the one of the genre [open] marked, each under
 * [chipModifier]'s modifier for it (a focus requester); each chip a button that [tap]s it: to show
 * the genre's box, or, the one [open], to fold it.
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
    val closeAction = stringResource(R.string.home_genre_close)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        for (label in labels) {
            val selected = label.label.genre.identity == open
            GenreChip(label, selected, if (selected) closeAction else showAction, chipModifier(label)) { tap(label) }
        }
    }
}

/**
 * A chip in *Genres*' panel (#439, #558, #772): a light fill in the text's colour; while [selected],
 * the text's colour, with the box's colour for the text. A button whose action a screen reader
 * names [action], and says is selected while its genre's box shows. [modifier] goes outside its
 * target (a focus requester).
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
        Text(placed.label.genre.label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

// Whether any row has [genre].
private fun TrackGenres.has(genre: String): Boolean = all.any { it.genre.identity == genre }

/** The test tags of *Genres*' button and its panel (#772), the panel's heading and *Close* as [Panel] makes them. */
const val GENRES_CHIP_TAG: String = "genres-chip"
const val GENRES_PANEL_TAG: String = "genres-panel"
const val GENRES_PANEL_HEADING_TAG: String = "genres-panel:heading"
const val GENRES_PANEL_CLOSE_TAG: String = "genres-panel:close"

/** The test tag of *Genres*' button under *Separate pages* (#772), which opens the genres' page. */
const val GENRES_PAGE_LINK_TAG: String = "genres-page-link"

/**
 * The test tag of a chip in *Genres*' panel, before its level (`track`, `artist`,
 * `album`), a colon, for an artist's the artist and a colon, and its identity:
 * `genre:artist:The Beatles:rock`.
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
    return prefix +
        placed.row.level.name
            .lowercase() + ":" + artist + placed.label.genre.identity
}
