package app.sleevesight.core.lyrics

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.resolution.Lookup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
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
         * Instrumental. Null too when normalizing the title removed what may tell one mix from another
         * ([removedVersion]): "Song - Off Vocal Version" would be asked for as "Song", whose vocal
         * mix, of the same album and length, LRCLIB would give. By the whole title, a composer's lead
         * included (`NormalizedTrack.searchTitle` leaves it out, #1179): LRCLIB keeps the names the
         * players give, and has Buniatishvili's K. 545 as `Mozart: Piano Sonata No. 16 …` only.
         */
        fun of(
            track: NormalizedTrack,
            nowPlaying: NowPlaying,
        ): LibraryQuery? {
            val seconds = nowPlaying.duration?.let(::seconds)?.takeIf { it in 1..MAX_SECONDS }
            val named = track.title.isNotBlank() && track.primary.isNotBlank()
            return seconds?.takeIf { named && !removedVersion(track) }?.let {
                LibraryQuery(track.primary, track.title, nowPlaying.album?.trim()?.takeIf(String::isNotEmpty), it)
            }
        }

        /**
         * Whether normalizing [track]'s title removed what may tell one recording from another (#997):
         * a variant (`NormalizedTrack.variant`: "(felt piano)", "(acoustic)"), or a dropped suffix
         * (`NormalizedTrack.dropped`) with a word not among [SAFE_WORDS] or a year. Only the suffixes
         * known to name the song's own recording pass ("2006 Remaster", "Radio Edit", "Promo 7" Edit",
         * "Album Version"); any other ("Off Vocal Version", "No Vocals Version", "Live") may be another
         * mix, which LRCLIB, asked by the plain title, would take for the vocal one.
         */
        fun removedVersion(track: NormalizedTrack): Boolean =
            track.variant != null ||
                track.dropped?.let { dropped -> words(dropped).any { it !in SAFE_WORDS && !YEAR.matches(it) } } == true

        /**
         * The words of a dropped suffix that leave the recording the song's own: a remaster, an edit,
         * the single's, the album's or the promo's version, the record's size (7", 10", 12"; #1060),
         * mono or stereo, the explicit or the clean one.
         */
        val SAFE_WORDS: Set<String> =
            setOf(
                "remaster",
                "remastered",
                "radio",
                "edit",
                "single",
                "promo",
                "7",
                "10",
                "12",
                "album",
                "version",
                "mono",
                "stereo",
                "explicit",
                "clean",
            )

        private val YEAR = Regex("[0-9]{4}")

        // [text]'s words, lowercased: its runs of letters and digits.
        private fun words(text: String): List<String> =
            text
                .lowercase()
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
                .split(' ')
                .filter { it.isNotEmpty() }

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

/**
 * [library] with one request in flight per query (#997): a second ask of a query already asked waits
 * for that request's answer rather than sending its own, so the run and the *Genres* panel, which may
 * both ask about one track, share one. The first caller makes the request; when it is cancelled, a
 * caller still waiting asks again itself. The process makes one, in `:wiring`, for every pipeline
 * built.
 */
class SharedLyricsLibrary(
    private val library: LyricsLibrary,
) : LyricsLibrary {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<LibraryQuery, CompletableDeferred<Lookup<LibraryLyrics>>>()

    override suspend fun lookUp(query: LibraryQuery): Lookup<LibraryLyrics> {
        while (true) {
            val mine = CompletableDeferred<Lookup<LibraryLyrics>>()
            val shared = mutex.withLock { inFlight[query].also { if (it == null) inFlight[query] = mine } }
            if (shared == null) return asked(query, mine)
            try {
                return shared.await()
            } catch (e: CancellationException) {
                // The first caller was cancelled, not this one: ask again; else this one was.
                currentCoroutineContext().ensureActive()
                if (!shared.isCancelled) throw e
            }
        }
    }

    // [query] asked by this caller, its answer given to those waiting on [mine].
    private suspend fun asked(
        query: LibraryQuery,
        mine: CompletableDeferred<Lookup<LibraryLyrics>>,
    ): Lookup<LibraryLyrics> {
        try {
            return library.lookUp(query).also { mine.complete(it) }
        } finally {
            mine.cancel()
            withContext(NonCancellable) { mutex.withLock { inFlight.remove(query) } }
        }
    }
}
