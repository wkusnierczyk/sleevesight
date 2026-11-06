package app.sleevesight.core.lyrics

import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.normalizer.Edition
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
         * Instrumental. Null too when normalizing the title removed what tells one mix from another
         * ([removedVersion]): "Song - Instrumental Version" is asked for as "Song", whose vocal mix,
         * of the same album and length, LRCLIB would give.
         */
        fun of(
            track: NormalizedTrack,
            nowPlaying: NowPlaying,
        ): LibraryQuery? {
            val seconds = nowPlaying.duration?.let(::seconds)?.takeIf { it in 1..MAX_SECONDS }
            val named = track.title.isNotBlank() && track.primary.isNotBlank()
            return seconds?.takeIf { named && !removedVersion(track, nowPlaying.title) }?.let {
                LibraryQuery(track.primary, track.title, nowPlaying.album?.trim()?.takeIf(String::isNotEmpty), it)
            }
        }

        /**
         * Whether normalizing [title], the player's, into [track]'s removed a word that names another
         * recording than the song's own (#997): an instrumental, karaoke or backing track, a remix, a
         * live or acoustic take and the like (`Edition.OTHER_RECORDINGS`, [VERSION_WORDS]), or a
         * variant (`NormalizedTrack.variant`: "(felt piano)", "(acoustic)").
         */
        fun removedVersion(
            track: NormalizedTrack,
            title: String,
        ): Boolean {
            val kept = words(track.title)
            return track.variant != null || words(title).any { it !in kept && it in VERSION_WORDS }
        }

        /** The words that name another recording: `Edition`'s, and the abbreviations players use. */
        val VERSION_WORDS: Set<String> =
            Edition.OTHER_RECORDINGS + setOf("inst", "instr", "backing", "dub", "rmx", "piano", "acapella", "cappella")

        // [text]'s words, lowercased: its runs of letters and digits.
        private fun words(text: String): Set<String> =
            text
                .lowercase()
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
                .split(' ')
                .filter { it.isNotEmpty() }
                .toSet()

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
