package app.sleevesight.app.session

import android.os.SystemClock

/**
 * When the user last acted in Sleevesight, on the `SystemClock.elapsedRealtime` clock ([clock]):
 * a tap on Write the pipeline took (`PipelineCoordinator`), and in Phase 2 the transport controls.
 * Each track change is recorded with the time since then (D15, `HistoryRecorder`). Thread-safe: the
 * pipeline records, the history's recorder reads.
 */
class UserActions(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val times = ArrayDeque<Long>()

    /** The user acted, now. */
    fun acted() {
        synchronized(times) {
            times.addLast(clock())
            while (times.size > KEPT) times.removeFirst()
        }
    }

    /**
     * The latest action at or before [at]: an action after it, taken before the change it times
     * was recorded, is not the last before it. Null when there was none (of the last [KEPT]).
     */
    fun lastAt(at: Long): Long? = synchronized(times) { times.lastOrNull { it <= at } }

    private companion object {
        /** Enough to reach back past the actions made while a change was being settled. */
        const val KEPT = 8
    }
}
