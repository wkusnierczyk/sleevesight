package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.sleevesight.app.R
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.app.pipeline.AiSearchFeed
import app.sleevesight.app.pipeline.GenreNotes
import app.sleevesight.app.pipeline.PanelLibrary
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.LaterLabels
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.pipeline.wantsLibrary
import app.sleevesight.core.pipeline.withLibrary
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.flowOf

/**
 * [track] with Instrumental on none of its genres' rows when *Find the lyrics* found lyrics pages
 * for it (#996, `TrackGenres.withLyricsFound`), as MusicBrainz's lyrics links drop it; else [track].
 * The answer is [search]'s, read only ([AiSearchFeed.answered]): subscribing asks nothing, and
 * tells the log of no kept answer served. Each subscription (another track, its length or album,
 * another feed) starts afresh, so the answer for the track before never stands for this one until
 * its own comes.
 */
@Composable
internal fun withLyricsFound(
    track: TrackState?,
    search: PanelSearch,
): TrackState? {
    val feed = search.feed
    val state =
        key(feed, track?.normalized, track?.nowPlaying?.duration, track?.nowPlaying?.album) {
            remember { track?.let { feed.answered(it, SearchKind.LYRICS) } ?: flowOf(AiSearch.None) }
                .collectAsState(AiSearch.None)
                .value
        }
    val answer = (state as? AiSearch.Answered)?.answer
    val found = answer is SearchAnswer.Lyrics && answer.links.isNotEmpty()
    return if (found && track != null) track.copy(genres = track.genres.withLyricsFound()) else track
}

/**
 * [track], weighed with the panel's own LRCLIB answer (#997, [withLibrary]), and, when LRCLIB could not
 * be reached for it, the panel's *Retry* ([retry]), which asks again for the AI's Instrumental.
 */
internal class LibraryWeighed(
    val track: TrackState?,
    val retry: (() -> Unit)? = null,
)

/**
 * Whether the panel asks LRCLIB itself about [joined], [playing] with the AI's genre list in its rows
 * (#997): the list added an Instrumental the run never saw, and the run is done with its labels, so
 * its own genres do not call for LRCLIB, now or later (an Instrumental a later label brought is the
 * run's to ask about, and the two would ask twice).
 */
internal fun panelAsksLibrary(
    playing: TrackState?,
    joined: TrackState?,
): Boolean {
    playing ?: return false
    val settled = playing.gathered && playing.genresAssembled && LaterLabels.ASKING !in playing.laterLabels.values
    val added = joined?.genres?.wantsLibrary() == true && !playing.genres.wantsLibrary()
    return settled && added
}

/**
 * [joined], [playing] with the AI's genre list in its rows, weighed with LRCLIB's answer (#997,
 * `TrackGenres.withLibrary`) when the panel asks itself ([panelAsksLibrary]), through [notes]
 * ([GenreNotes.libraryLyrics]); not reached, [TrackState.libraryUnreachable] set, with the panel's
 * *Retry* ([GenreNotes.retryLibrary]). Else [joined] as it is.
 */
@Composable
internal fun withLibrary(
    playing: TrackState?,
    joined: TrackState?,
    notes: GenreNotes,
): LibraryWeighed {
    val asks = panelAsksLibrary(playing, joined)
    val answer =
        key(notes, joined?.normalized, joined?.nowPlaying?.duration, joined?.nowPlaying?.album, asks) {
            remember { joined?.takeIf { asks }?.let(notes::libraryLyrics) ?: flowOf(null) }
                .collectAsState(null)
                .value
        }
    return when {
        joined == null || answer == null -> LibraryWeighed(joined)
        answer is PanelLibrary.Answered ->
            LibraryWeighed(
                joined.copy(genres = joined.genres.withLibrary(answer.lyrics)),
            )
        else -> LibraryWeighed(joined.copy(libraryUnreachable = true)) { notes.retryLibrary(joined) }
    }
}

// LRCLIB, asked whether the track has lyrics for its Instrumental (#997), when [unreachable]: its
// line, with *Retry*: the panel's own ([panelRetry]) when it asked, else the run's ([retry]).
@Composable
internal fun LibraryRetry(
    unreachable: Boolean,
    panelRetry: (() -> Unit)?,
    retry: (Collection<SourceId>) -> (() -> Unit)?,
    toClose: () -> Unit,
) {
    if (!unreachable) return
    val name = sourceName(SourceId.LRCLIB)
    RetryLine(
        stringResource(R.string.home_genre_source_unreachable, name),
        name,
        panelRetry ?: retry(listOf(SourceId.LRCLIB)),
        afterRetry = toClose,
    )
}
