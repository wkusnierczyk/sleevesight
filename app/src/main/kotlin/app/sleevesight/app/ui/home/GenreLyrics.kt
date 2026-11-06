package app.sleevesight.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import app.sleevesight.app.pipeline.AiSearch
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.synthesis.SearchAnswer
import app.sleevesight.core.synthesis.SearchKind
import kotlinx.coroutines.flow.flowOf

/**
 * [track] with Instrumental on none of its genres' rows when *Find the lyrics* found lyrics pages
 * for it (#996, `TrackGenres.withLyricsFound`), as MusicBrainz's lyrics links drop it; else [track].
 * The answer is [search]'s, read as the *Lyrics* panel's [AiSearchBlock] reads it: subscribing
 * asks nothing. Each subscription (another track, its length or album, another feed) starts afresh,
 * so the answer for the track before never stands for this one until its own comes.
 */
@Composable
internal fun withLyricsFound(
    track: TrackState?,
    search: PanelSearch,
): TrackState? {
    val feed = search.feed
    val state =
        key(feed, track?.normalized, track?.nowPlaying?.duration, track?.nowPlaying?.album) {
            remember { track?.let { feed.search(it, SearchKind.LYRICS) } ?: flowOf(AiSearch.None) }
                .collectAsState(AiSearch.None)
                .value
        }
    val answer = (state as? AiSearch.Answered)?.answer
    val found = answer is SearchAnswer.Lyrics && answer.links.isNotEmpty()
    return if (found && track != null) track.copy(genres = track.genres.withLyricsFound()) else track
}
