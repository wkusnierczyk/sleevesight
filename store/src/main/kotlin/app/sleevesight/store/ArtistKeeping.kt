package app.sleevesight.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * *Keep what sources said about each artist* (#420), as [keeps] reads it at each call, shared by
 * the two stores that would keep it: the artist-level cache ([StoreArtistResults]) and the
 * retrieval cache ([StorePipelineCache]), which keeps a track's result with what it says about the
 * artists ([RetrievalCodec.aboutArtists]). [clearAll] deletes all of it ([Caches.clearArtists]).
 *
 * A write of what was said about an artist ([ifOn]) and the [clear] take turns, so nothing is kept
 * after the clear that follows the switch's turning off: a write that read the switch on either
 * ends before the clear starts, and is cleared, or starts after it and finds the switch off,
 * provided the switch reads off by the time the clear is called. A clear that failed is tried again
 * by the next read of either store while the switch is off ([retryClear]), so a database that
 * failed once does not leave what was kept in place for the rest of the process.
 */
class ArtistKeeping(
    private val keeps: () -> Boolean,
    private val clearAll: suspend () -> Unit,
) {
    private val turns = Mutex()

    // Whether the last clear failed, so a read while the switch is off tries it again.
    @Volatile private var uncleared = false

    /** Whether the switch is on now. */
    val on: Boolean get() = keeps()

    /** Runs [write] if the switch is on, in turn with [clear]. */
    suspend fun ifOn(write: suspend () -> Unit) {
        turns.withLock { if (keeps()) write() }
    }

    /**
     * Deletes what is kept about artists, after any write in progress and before any that starts
     * after it. Returns whether it did: false when the database failed, and then a read while the
     * switch is off tries again ([retryClear]). It does not throw, but for cancellation.
     */
    suspend fun clear(): Boolean = turns.withLock { clearHeld() }

    /**
     * Clears again if the switch is off and the last clear failed; for the stores' reads. Both are
     * checked before taking the lock, so a read costs nothing in the usual case, and again once it
     * is held, so a retry that waited behind another, which succeeded, or behind the switch's
     * turning on, does nothing.
     */
    suspend fun retryClear() {
        if (!uncleared || keeps()) return
        turns.withLock { if (uncleared && !keeps()) clearHeld() }
    }

    // The clear, with [turns] held.
    private suspend fun clearHeld(): Boolean {
        val cleared =
            try {
                clearAll()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (
                // A database that failed; the next read while the switch is off tries again.
                @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
            ) {
                false
            }
        uncleared = !cleared
        return cleared
    }

    companion object {
        /** Always on, so nothing to clear: the CLI's, whose cache is the developer's, and the tests'. */
        val ALWAYS: ArtistKeeping = ArtistKeeping({ true }) {}

        /**
         * The switch as [keeps] reads it, over the caches [caches] gives at the first clear, cleared
         * in [io] ([Caches] makes blocking database calls).
         */
        fun over(
            caches: () -> Caches,
            io: CoroutineContext,
            keeps: () -> Boolean,
        ): ArtistKeeping = ArtistKeeping(keeps) { withContext(io) { caches().clearArtists() } }
    }
}
