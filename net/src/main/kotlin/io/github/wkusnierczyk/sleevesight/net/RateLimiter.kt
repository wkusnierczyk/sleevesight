package io.github.wkusnierczyk.sleevesight.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Spaces the requests to one host at least [interval] apart. Callers wait in turn, first come first
 * served (the mutex is fair); a cancelled caller gives up its turn.
 */
internal class RateLimiter(
    private val interval: Duration,
    private val timeSource: TimeSource.WithComparableMarks,
) {
    private val mutex = Mutex()
    private var next: ComparableTimeMark? = null

    // Detekt 1.23.8 cannot resolve the suspending calls inside (from libraries built with a newer
    // Kotlin than its own), so it takes the modifier for redundant; the compiler requires it.
    @Suppress("RedundantSuspendModifier")
    suspend fun acquire() {
        mutex.withLock {
            next?.let { mark ->
                val wait = mark - timeSource.markNow()
                if (wait.isPositive()) delay(wait)
            }
            next = timeSource.markNow() + interval
        }
    }
}
