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
import app.sleevesight.core.model.SourceId
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
 * [joined], [playing] with the AI's genre list in its rows, weighed with LRCLIB's answer (#997,
 * `TrackGenres.withLibrary`) when the list added an Instrumental the run never saw, so never asked
 * LRCLIB about: asked through [notes] ([GenreNotes.libraryLyrics]) only then, once per track. An
 * Instrumental the sources' tags gave is the run's to ask about; with it, nothing is asked here.
 */
@Composable
internal fun withLibrary(
    playing: TrackState?,
    joined: TrackState?,
    notes: GenreNotes,
): TrackState? {
    val wants = playing != null && joined != null && joined.genres.wantsLibrary() && !playing.genres.wantsLibrary()
    val answer =
        key(notes, joined?.normalized, joined?.nowPlaying?.duration, wants) {
            remember { joined?.takeIf { wants }?.let(notes::libraryLyrics) ?: flowOf(null) }
                .collectAsState(null)
                .value
        }
    return if (answer != null && joined != null) joined.copy(genres = joined.genres.withLibrary(answer)) else joined
}

// LRCLIB, asked whether the track has lyrics for its Instrumental (#997), when [unreachable]: its
// line, with *Retry*; nothing else.
@Composable
internal fun LibraryRetry(
    unreachable: Boolean,
    retry: (SourceId) -> (() -> Unit)?,
    toClose: () -> Unit,
) {
    if (!unreachable) return
    val name = sourceName(SourceId.LRCLIB)
    RetryLine(
        stringResource(R.string.home_genre_source_unreachable, name),
        name,
        retry(SourceId.LRCLIB),
        afterRetry = toClose,
    )
}
