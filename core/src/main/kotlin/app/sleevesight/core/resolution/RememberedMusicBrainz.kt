package app.sleevesight.core.resolution

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * MusicBrainz's answers, kept in memory for a short time ([lifetime]), so that asking again soon
 * after sends no request (`design/architecture.md`, *Preparing the next track*, D13). Each question
 * is answered by [musicBrainz] once; a search by name, a recording, a work and an artist search
 * are each kept by what was asked, at most [capacity] of each kind, the least recently used
 * dropped first. An answer that could not be obtained ([Lookup.Failed]) is not kept, so the next
 * question asks again; one that MusicBrainz gave, found or not, is.
 *
 * What it serves: preparing the next track looks it up from the player's queue item, which has
 * no length (or album), and so resolves it by name only; when the track then plays, its own run
 * resolves it again with the player's length, and finds the same questions answered here. The
 * recording is still chosen by its length, by the same resolver and the same rules
 * ([RecordingResolver]): only the answers come from memory. A run of the same artists soon after
 * (Last.fm's check of an artist's name, a track repeated) asks nothing either.
 *
 * A short lifetime, not the retrieval cache's (D9): the answers are kept for the track about to
 * play, and MusicBrainz's data changes no faster than that. In memory only, for the process.
 * Whatever *Keep what sources said about each artist* says (#420): that switch is about what is
 * stored on the phone, and this is half an hour of memory that also serves the track's own lookups.
 *
 * A recording is kept by its id and by whether its tags were asked for ([tags], as [musicBrainz]
 * asks: not while *Show genres* is off, #444), so one looked up without them is not served once
 * they are wanted.
 */
class RememberedMusicBrainz(
    private val musicBrainz: MusicBrainzLookup,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val lifetime: Duration = LIFETIME,
    private val capacity: Int = CAPACITY,
    private val tags: () -> Boolean = { true },
) : MusicBrainzLookup {
    private val searches = Memory<Pair<String, String>, List<MbRecording>>()
    private val recordings = Memory<Pair<String, Boolean>, MbRecordingDetail>()
    private val works = Memory<String, MbWork>()
    private val artists = Memory<String, MbArtistSearch>()

    override suspend fun searchRecordings(
        title: String,
        artist: String,
    ): Lookup<List<MbRecording>> = searches.answer(title to artist) { musicBrainz.searchRecordings(title, artist) }

    override suspend fun recording(mbid: String): Lookup<MbRecordingDetail> =
        recordings.answer(mbid to tags()) { musicBrainz.recording(mbid) }

    override suspend fun work(mbid: String): Lookup<MbWork> = works.answer(mbid) { musicBrainz.work(mbid) }

    override suspend fun searchArtists(name: String): Lookup<MbArtistSearch> =
        artists.answer(name) { musicBrainz.searchArtists(name) }

    /** Forgets every answer kept, for *Clear history* (#547): the next question asks again. */
    suspend fun forget() {
        searches.forget()
        recordings.forget()
        works.forget()
        artists.forget()
    }

    // One kind of question's answers, by what was asked.
    private inner class Memory<K : Any, T> {
        private inner class Kept(
            val answer: Lookup<T>,
            val at: TimeMark,
        )

        private val mutex = Mutex()

        // Insertion-ordered; an entry is moved to the end when used, so the first is the least recent.
        private val kept = LinkedHashMap<K, Kept>()

        suspend fun answer(
            key: K,
            ask: suspend () -> Lookup<T>,
        ): Lookup<T> {
            kept(key)?.let { return it }
            val answer = ask()
            if (answer !is Lookup.Failed) keep(key, answer)
            return answer
        }

        @Suppress("RedundantSuspendModifier") // Detekt 1.23.8 does not see `Mutex.withLock` as suspending; it is.
        suspend fun forget() = mutex.withLock { kept.clear() }

        @Suppress("RedundantSuspendModifier") // Detekt 1.23.8 does not see `Mutex.withLock` as suspending; it is.
        private suspend fun kept(key: K): Lookup<T>? =
            mutex.withLock {
                val entry = kept.remove(key)
                if (entry == null || entry.at.elapsedNow() >= lifetime) {
                    null
                } else {
                    kept[key] = entry
                    entry.answer
                }
            }

        @Suppress("RedundantSuspendModifier") // Detekt 1.23.8 does not see `Mutex.withLock` as suspending; it is.
        private suspend fun keep(
            key: K,
            answer: Lookup<T>,
        ) = mutex.withLock {
            kept.remove(key)
            kept[key] = Kept(answer, timeSource.markNow())
            while (kept.size > capacity) kept.remove(kept.keys.first())
        }
    }

    companion object {
        /**
         * Longer than most tracks, so the answers for the next one are kept until it has played
         * on, even after a long track before it.
         */
        val LIFETIME: Duration = 30.minutes

        /**
         * Of each kind: a track's resolution asks one search, and a few recordings and works, so
         * this keeps a few dozen tracks' answers at least, at a few kilobytes an answer.
         */
        const val CAPACITY: Int = 100
    }
}
