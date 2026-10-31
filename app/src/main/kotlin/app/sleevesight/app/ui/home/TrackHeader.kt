package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings

// The track's header on Home: its key data, and under the album the line of buttons that open in
// place (#724, #772).

/**
 * The key data of [header] ([KeyData]: the title, the artists, the album and its year), then under
 * the album the line of buttons that open in place ([UnderAlbum]), each a child of Home's column.
 */
@Composable
internal fun TrackHeader(
    shown: Showing,
    header: NowPlaying,
    line: UnderAlbumLine,
    actions: NowPlayingActions,
) {
    KeyData(remember(header) { actions.normalize(header) }, header, headerYears(shown.track, header))
    UnderAlbum(shown, header, line, actions)
}

/**
 * What the line under the album needs besides the track: its [memory] of the panels open, the
 * [settings] (null until their first read), what a genre's box shows above its table ([notes]),
 * and, under *Separate pages*, the way to the genres' page ([genresPage], #772).
 */
internal class UnderAlbumLine(
    val memory: InPlaceMemory,
    val settings: Settings?,
    val notes: PanelNotes,
    val genresPage: (() -> Unit)? = null,
)

/**
 * Under the album, the line of buttons that open in place ([InPlaceLine], #724), whatever *Layout*
 * says: *Dates*, for every track ([datesItem]), its *Retry* asking MusicBrainz again ([Retries])
 * and its link opening *Settings → Sources*; then *Genres* (#772), for every track while *Show
 * genres* is on ([genresItem]), its *Retry* asking a label source again and its link opening
 * *Settings → Sources* or *Settings → Genres*; under *Separate pages*, it opens the genres' page;
 * then *Lyrics* while *Lyrics links* is on ([lyricsItem], #728), its *Retry* asking MusicBrainz
 * again and its links opening the settings: *Sources*, and for *Lyrics*' search *Appearance*.
 * *Dates* and *Lyrics* open in place under either.
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
    val dates = datesItem(homeDates(track, header), NoDates.of(track), retry, openSources)
    val lyrics =
        if (shown.lyricsLinks) {
            val links = remember(track, shown.lyricsSearch) { homeLyrics(track, shown.lyricsSearch) }
            lyricsItem(links, retry, openSources) { actions.openSettingsAt(SettingsTarget.lyricsSearch) }
        } else {
            null
        }
    val genres = genresItem(track, line.settings, line.notes, retries, actions.openSettingsAt, line.genresPage)
    InPlaceLine(listOfNotNull(dates, genres, lyrics), line.memory, playing)
}
