package app.sleevesight.store

import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.KeptPart
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The adapters' [ArtistResults] over the artist-level cache ([Caches.artistResults], #238). [Caches]
 * makes blocking database calls, so each runs in [io], the caller's I/O dispatcher, as in
 * [StorePipelineCache]. [caches] is asked for at the first call, not before: the app's wiring is
 * made at start, and its cache database opens only once the first run is acknowledged.
 *
 * [keeping] is *Keep what sources said about each artist* (#420): while it is off, nothing is read
 * or kept, so each track's artists are looked up again, and the adapters keep nothing in memory in
 * its place either ([persists] stays true), since the switch is about what is kept of the artists
 * heard. [clear] deletes what was kept, here and in the retrieval cache ([Caches.clearArtists]), in
 * turn with the writes ([ArtistKeeping]). A clear that failed is tried again at each read while the
 * switch is off, so a database that failed once does not leave what was kept for the rest of the
 * process.
 *
 * It does not throw, as [ArtistResults] asks: a database that fails to open, read or write misses
 * and keeps nothing, so the adapter asks its source each time (with no cache, the adapters keep
 * some answers in memory instead; [persists] stays true here). Cancellation is rethrown.
 */
class StoreArtistResults(
    private val caches: () -> Caches,
    private val io: CoroutineContext,
    private val keeping: ArtistKeeping,
) : ArtistResults {
    // Whether the last clear failed, so a read while the switch is off tries it again.
    @Volatile private var uncleared = false

    override suspend fun get(
        part: ArtistPart,
        key: String,
    ): KeptPart? {
        if (keeping.on) return failingAs(null) { caches().artistResults.get(part, key) }
        if (uncleared) clear()
        return null
    }

    override suspend fun keep(
        part: ArtistPart,
        key: String,
        value: String,
    ) {
        keeping.ifOn { failingAs(false) { caches().artistResults.put(part, key, value) } }
    }

    /**
     * Deletes what is kept about artists ([Caches.clearArtists]), after any write in progress.
     * Returns whether it did: false when the database failed, and then a read while the switch is
     * off tries again.
     */
    suspend fun clear(): Boolean {
        val cleared =
            keeping.clearing {
                failingAs(false) {
                    caches().clearArtists()
                    true
                }
            }
        uncleared = !cleared
        return cleared
    }

    // [call] in [io], or [failed] when it throws anything but cancellation.
    @Suppress("RedundantSuspendModifier") // Detekt 1.23.8 does not see `withContext` as suspending; it is.
    private suspend fun <T> failingAs(
        failed: T,
        call: () -> T,
    ): T =
        try {
            withContext(io) { call() }
        } catch (e: CancellationException) {
            throw e
        } catch (
            // A store failure is a miss; the adapter asks its source instead, so nothing is lost.
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            failed
        }
}
