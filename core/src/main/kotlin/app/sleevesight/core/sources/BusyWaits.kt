package app.sleevesight.core.sources

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How long a run may wait out a busy MusicBrainz (*Wait when busy*, #1120, `Settings.musicBrainzWait`):
 * one allowance of [total] for all of the run's MusicBrainz requests, so a lookup that asks several
 * (a search, a recording, a work) waits no longer in all than the setting says. A coroutine context
 * element the run's work inherits, set by the run (`TrackRun`); the client's retries of a busy answer
 * draw on it through `:wiring`, and a request outside a run has an allowance of its own.
 *
 * What counts is the time some request spends [waiting] to ask again: the backoff and the turn at the
 * host after it. Waits that overlap count once ([waited]), so requests waiting together do not use it
 * up twice as fast. The gather's deadline is longer by what was [waited] (`GatherBudget`), so the time
 * spent waiting out MusicBrainz is not cut short, while the deadline for a source that stalls stays
 * as it was.
 */
class BusyWaits(
    val total: Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : AbstractCoroutineContextElement(BusyWaits) {
    private val lock = Any()

    // The time waited before the waits under way began, and when they began; guarded by [lock].
    private var before = Duration.ZERO
    private var since: TimeMark? = null

    /** How many requests are waiting now. */
    private val waiters = MutableStateFlow(0)

    /** The time some request has spent waiting, overlapping waits counted once. */
    val waited: Duration
        get() = synchronized(lock) { before + (since?.elapsedNow() ?: Duration.ZERO) }

    /** How much longer the run's requests may wait. */
    val left: Duration get() = (total - waited).coerceAtLeast(Duration.ZERO)

    /** Whether a request is waiting now. */
    val waitingNow: Boolean get() = waiters.value > 0

    /** Runs [block], a wait before asking a busy MusicBrainz again, counted as [waited]. */
    suspend fun <T> waiting(block: suspend () -> T): T {
        begin()
        try {
            return block()
        } finally {
            end()
        }
    }

    /** Returns once no request is waiting. */
    @Suppress("RedundantSuspendModifier") // Detekt does not see `Flow.first` as suspending.
    suspend fun idle() {
        waiters.first { it == 0 }
    }

    private fun begin() {
        synchronized(lock) {
            if (waiters.value == 0) since = timeSource.markNow()
            waiters.update { it + 1 }
        }
    }

    private fun end() {
        synchronized(lock) {
            waiters.update { it - 1 }
            if (waiters.value == 0) {
                before += since?.elapsedNow() ?: Duration.ZERO
                since = null
            }
        }
    }

    companion object Key : CoroutineContext.Key<BusyWaits> {
        /** The allowance of the work calling this, or null outside a run. */
        @Suppress("RedundantSuspendModifier") // Detekt does not see `currentCoroutineContext` as suspending.
        suspend fun current(): BusyWaits? = currentCoroutineContext()[BusyWaits]
    }
}
