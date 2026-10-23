package app.sleevesight.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * *Keep what sources said about each artist* (#420), as [keeps] reads it at each call, shared by
 * the two stores that would keep it: the artist-level cache ([StoreArtistResults]) and the
 * retrieval cache ([StorePipelineCache]), which keeps a track's result with the artist-level lines
 * in it. A write of what was said about an artist ([ifOn]) and the clear of all of it ([clearing])
 * take turns, so nothing is kept after the clear that follows the switch's turning off: a write
 * that read the switch on either ends before the clear starts, and is cleared, or starts after it
 * and finds the switch off, provided the switch reads off by the time the clear is called.
 */
class ArtistKeeping(
    private val keeps: () -> Boolean,
) {
    private val turns = Mutex()

    /** Whether the switch is on now. */
    val on: Boolean get() = keeps()

    /** Runs [write] if the switch is on, in turn with [clearing]. */
    suspend fun ifOn(write: suspend () -> Unit) {
        turns.withLock { if (keeps()) write() }
    }

    /** Runs [clear], after any write in progress, and before any that starts after it. */
    suspend fun <T> clearing(clear: suspend () -> T): T = turns.withLock { clear() }

    companion object {
        /** Always on: the CLI's, whose cache is the developer's, and the tests'. */
        val ALWAYS: ArtistKeeping = ArtistKeeping { true }
    }
}
