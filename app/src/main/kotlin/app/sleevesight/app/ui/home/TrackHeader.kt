package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.FoundAs
import app.sleevesight.core.settings.Settings

// The track's header on Home: its key data, and under the album the line of buttons that open in
// place (#724, #772).

/**
 * The key data of [header] ([KeyData]: the title, the artists, the album, and under it its record
 * label and year), then under it the line of buttons that open in place ([UnderAlbum]), each a child
 * of Home's column. The artists under the title are held back until the track's people are [named]
 * ([peopleNamed], #1355); the line under the album likewise until the release's labels are known
 * ([releaseLabels], #729), as [line]'s memory keeps the wait. Their room is kept at the bottom of the
 * key data's block, so the buttons do not move when they come in (#1407).
 */
@Composable
internal fun TrackHeader(
    shown: Showing,
    header: NowPlaying,
    line: UnderAlbumLine,
    actions: NowPlayingActions,
    named: Boolean = true,
) {
    val normalized = remember(header) { actions.normalize(header) }
    val split = remember(shown.track, normalized) { splitOf(shown.track, normalized) }
    val labels = releaseLabels(shown, header, line.settings, actions)
    val known = shown.cleared || labels is AlbumLabels.Known
    val own = rememberNamingMemory()
    val labelHeld = !waitedFor(line.labelMemory ?: own, shown.playing, known)
    KeyData(
        normalized,
        header,
        headerYears(shown.track, header),
        split,
        held = !named,
        notes = line.notes,
        foundAs = shown.track?.foundAs,
        labels = labels,
        labelHeld = labelHeld,
    )
    UnderAlbum(shown, header, line, actions)
}

/**
 * The record labels of the release of [header]'s album being played (#729, [albumLabels]), with
 * [settings] (MusicBrainz taken as on until their first read, as the cover's back takes it): the
 * release the back of the cover shows (#984), its tracklist looked up as soon as the track's release
 * is known rather than on the first flip, so the label is there without one. One lookup per release,
 * through the remembered MusicBrainz answers the back reads too, so a flip then asks nothing more.
 */
@Composable
private fun releaseLabels(
    shown: Showing,
    header: NowPlaying,
    settings: Settings?,
    actions: NowPlayingActions,
): AlbumLabels {
    val states by actions.tracklists.states.collectAsState()
    val choices by actions.tracklists.choices.collectAsState()
    // A feed that looks nothing up (a preview's) gives no label: none is waited for.
    val on = settings?.isEnabled(SourceId.MUSICBRAINZ) != false && actions.tracklists.looksUp
    val key = if (on) tracklistKey(shown.track, header, choices) else null
    // Each look ahead gives up the one before it, still asked (a skipped track's), so it does not
    // queue ahead of this one's under MusicBrainz's one request a second.
    LaunchedEffect(key, actions.tracklists) { key?.let(actions.tracklists::lookAhead) }
    val labels = albumLabels(shown.track, header, on, states, choices)
    // The labels last known on this track stand while its key changes (a choice among albums of one
    // name, a run again) and the new one is looked up, rather than the year alone in between.
    var last by remember { mutableStateOf<Pair<NowPlaying, AlbumLabels.Known>?>(null) }
    val kept = last?.takeIf { (track, _) -> TrackIdentity.playsOn(track, shown.playing) }?.second
    SideEffect { if (labels is AlbumLabels.Known) last = shown.playing to labels }
    return if (labels == AlbumLabels.Looking && kept != null) kept else labels
}

/**
 * What the lines under the album need besides the track: its [memory] of the panels open, the
 * [settings] (null until their first read), what a genre's box shows above its table ([notes]),
 * under *Separate pages* the way to the genres' page ([genresPage], #772), and the wait for the
 * record label ([labelMemory], #729), kept above whatever comes and goes with the track (none: one
 * of the header's own).
 */
internal class UnderAlbumLine(
    val memory: InPlaceMemory,
    val settings: Settings?,
    val notes: PanelNotes,
    val genresPage: (() -> Unit)? = null,
    val labelMemory: NamingMemory? = null,
)

/**
 * Under the album, the line of buttons that open in place ([InPlaceLine], #724), whatever *Layout*
 * says: *Dates*, for every track ([datesItem]), its *Retry* asking MusicBrainz again ([Retries])
 * and its link opening *Settings → Sources*; then *Genres* (#772), for every track while *Show
 * genres* is on ([genresItem]), its *Retry* asking a label source again and its link opening
 * *Settings → Sources* or *Settings → Genres*; under *Separate pages*, it opens the genres' page;
 * then *Lyrics* while *Lyrics links* is on ([lyricsItem], #728), its *Retry* asking MusicBrainz
 * again and its links opening the settings: *Sources*, and for *Lyrics*' search *Appearance*.
 * *Dates* and *Lyrics* open in place under either. Each offers *Search with AI* under its empty
 * state (#773). With *One panel for Dates, Genres and Lyrics* on (#1128, the default), the three
 * open one shared panel, their buttons its tabs, *Genres* in it under *Separate pages* too.
 */
@Composable
internal fun UnderAlbum(
    shown: Showing,
    header: NowPlaying,
    line: UnderAlbumLine,
    actions: NowPlayingActions,
) {
    val track = shown.track
    val playing = shown.playing
    val retries = Retries(shown.view, playing, actions.askAgain)
    val retry = retries.of(SourceId.MUSICBRAINZ)
    val openSources = { actions.openSettingsAt(SettingsTarget.sources) }
    val search = PanelSearch(actions.aiSearches, track, header)
    // The album's release group's first release, looked up when *Dates* opens (#1099).
    val firsts by actions.releaseGroups.firsts.collectAsState()
    val group = editionGroup(track, header)
    val look = remember(group, actions.releaseGroups) { group?.let { { actions.releaseGroups.look(it) } } }
    // Worked out once per track, header and group lookup, not at every recomposition (#1147).
    val sentences = remember(track, header, firsts) { homeDates(track, header, firsts) }
    val dates = datesItem(sentences, NoDates.of(track), retry, openSources, search, look)
    val lyrics =
        if (shown.lyricsLinks) {
            val links = remember(track, shown.lyricsSearch) { homeLyrics(track, shown.lyricsSearch) }
            lyricsItem(links, retry, openSources, search, line.notes) {
                actions.openSettingsAt(SettingsTarget.lyricsSearch)
            }
        } else {
            null
        }
    // One panel for the three (#1128), on until the settings are read, as by default: *Genres* opens
    // in it under *Separate pages* too.
    val onePanel = line.settings?.onePanel ?: true
    val page = line.genresPage.takeUnless { onePanel }
    val genres = genresItem(track, line.settings, line.notes, retries, actions.openSettingsAt, page, search)
    InPlaceLine(listOfNotNull(dates, genres, lyrics), line.memory, playing, onePanel)
}

/** The spelling the sources were asked with in place of the player's title (#1293): "Found as “{title}”". */
@Composable
internal fun foundAsLine(found: FoundAs): String = stringResource(R.string.home_spelling_found, found.title)
