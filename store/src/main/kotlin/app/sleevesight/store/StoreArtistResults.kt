package app.sleevesight.store

import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The adapters' [ArtistResults] over the artist-level cache ([Caches.artistResults], #238). [Caches]
 * makes blocking database calls, so each runs in [io], the caller's I/O dispatcher, as in
 * [StorePipelineCache]. [caches] is asked for at the first call, not before: the app's wiring is
 * made at start, and its cache database opens only once the first run is acknowledged.
 *
 * It does not throw, as [ArtistResults] asks: a database that fails to open, read or write is a
 * miss, and keeps nothing, so the adapter asks its source as with no cache. Cancellation is
 * rethrown.
 */
class StoreArtistResults(
    private val caches: () -> Caches,
    private val io: CoroutineContext,
) : ArtistResults {
    override suspend fun get(
        part: ArtistPart,
        key: String,
    ): String? = failingAs(null) { caches().artistResults.get(part, key) }

    override suspend fun keep(
        part: ArtistPart,
        key: String,
        value: String,
    ) {
        failingAs(false) { caches().artistResults.put(part, key, value) }
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
