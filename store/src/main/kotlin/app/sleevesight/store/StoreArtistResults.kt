package app.sleevesight.store

import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.sources.ArtistPart
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.FreshLookups
import app.sleevesight.core.sources.KeptPart
import kotlinx.coroutines.currentCoroutineContext
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
 * heard. Its writes take turns with [ArtistKeeping.clear], and a read while the switch is off
 * tries again a clear that failed ([ArtistKeeping.retryClear]).
 *
 * It does not throw, as [ArtistResults] asks: a database that fails to open, read or write misses
 * and keeps nothing, so the adapter asks its source each time (with no cache, the adapters keep
 * some answers in memory instead; [persists] stays true here). Cancellation is rethrown.
 *
 * Nothing found by work started before *Clear history* is kept after it ([epoch], #547).
 *
 * Work that looks its sources up afresh ([FreshLookups], *Look again*, #617) reads nothing here,
 * so the adapters ask their sources; what they find is kept as usual.
 */
class StoreArtistResults(
    private val caches: () -> Caches,
    private val io: CoroutineContext,
    private val keeping: ArtistKeeping,
    private val epoch: ClearEpoch = ClearEpoch(),
) : ArtistResults {
    override suspend fun get(
        part: ArtistPart,
        key: String,
    ): KeptPart? =
        when {
            FreshLookups.active() -> null
            keeping.on -> failingAs(null) { caches().artistResults.get(part, key) }
            else -> {
                keeping.retryClear()
                null
            }
        }

    override suspend fun keep(
        part: ArtistPart,
        key: String,
        value: String,
    ) {
        val work = currentCoroutineContext()
        keeping.ifOn { epoch.ifCurrent(work) { failingAs(false) { caches().artistResults.put(part, key, value) } } }
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
