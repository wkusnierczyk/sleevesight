package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.sleevesight.app.ui.settings.SettingsTarget
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.SourceId

// The track's header on Home: its key data, and under the album the line of buttons that open in
// place (#724).

/**
 * The key data of [header] ([KeyData]: the title, the artists, the album and its year), then under
 * the album the line of buttons that open in place ([UnderAlbum]), each a child of Home's column.
 */
@Composable
internal fun TrackHeader(
    shown: Showing,
    header: NowPlaying,
    memory: InPlaceMemory,
    actions: NowPlayingActions,
) {
    KeyData(remember(header) { actions.normalize(header) }, header, headerYears(shown.track, header))
    UnderAlbum(shown, header, memory, actions)
}

/**
 * Under the album, the line of buttons that open in place ([InPlaceLine], #724), whatever *Layout*
 * says: *Dates*, for every track ([datesItem]), and *Lyrics* while *Lyrics links* is on
 * ([lyricsItem], #728), each one's *Retry* asking MusicBrainz again ([Retries]) and its links opening
 * the settings: *Sources*, and for *Lyrics*' search *Appearance*. #772 adds *Genres* to
 * [InPlaceLine]'s items here.
 */
@Composable
internal fun UnderAlbum(
    shown: Showing,
    header: NowPlaying,
    memory: InPlaceMemory,
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
    InPlaceLine(listOfNotNull(dates, lyrics), memory, playing)
}
