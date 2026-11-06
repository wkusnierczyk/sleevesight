package app.sleevesight.core.lyrics

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.resolution.Lookup
import kotlin.time.Duration

/**
 * What LRCLIB, an open lyrics library, says of a track (#997; `design/design.md`, *Genre labels*):
 * that it has lyrics ([LYRICS]: plain or synced lyrics, not marked instrumental), that it is marked
 * [INSTRUMENTAL], or neither ([NONE]: no match, or a match with no lyrics and no mark). The name is
 * stored in the retrieval cache, so a value is never renamed.
 */
enum class LibraryLyrics {
    LYRICS,
    INSTRUMENTAL,
    NONE,
}

/**
 * The track as LRCLIB's `/api/get` is asked for it (#997): the first credited [artist], the [title]
 * as the sources name it, the [album] the player reports, if any, and the player's length in whole
 * [seconds], which LRCLIB matches within 2 seconds.
 */
data class LibraryQuery(
    val artist: String,
    val title: String,
    val album: String?,
    val seconds: Long,
) {
    companion object {
        /** The longest length LRCLIB takes, in seconds (an hour). */
        const val MAX_SECONDS: Long = 3600

        private const val MILLIS = 1000L
        private const val HALF = 500L

        /**
         * The query for [track], played as [nowPlaying] reports it; null without a title, an artist,
         * or the player's length (or one LRCLIB does not take, below a second or over an hour): a
         * match not held to the track's length could be another recording's, and drop a real
         * Instrumental.
         */
        fun of(
            track: NormalizedTrack,
            nowPlaying: NowPlaying,
        ): LibraryQuery? {
            val seconds = nowPlaying.duration?.let(::seconds)?.takeIf { it in 1..MAX_SECONDS }
            val named = track.title.isNotBlank() && track.primary.isNotBlank()
            return seconds?.takeIf { named }?.let {
                LibraryQuery(track.primary, track.title, nowPlaying.album?.trim()?.takeIf(String::isNotEmpty), it)
            }
        }

        /** [duration] rounded to whole seconds, half a second up. */
        fun seconds(duration: Duration): Long = (duration.inWholeMilliseconds + HALF) / MILLIS
    }
}

/**
 * LRCLIB's `/api/get` (#997): `LrclibClient` in `:sources`, a fake in tests. [Lookup.NotFound] when
 * nothing matches (a 404), which says nothing of the track; [Lookup.Failed] when it could not be
 * reached or did not answer usably.
 */
fun interface LyricsLibrary {
    suspend fun lookUp(query: LibraryQuery): Lookup<LibraryLyrics>
}
