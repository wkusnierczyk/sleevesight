package app.sleevesight.app.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Keeps the session history and the track changes for the user's *Keep history for* ([lifetime];
 * null keeps them for ever), and clears them on *Clear history*. Both go through [recorder]'s
 * queue, so they are ordered with the history's writes.
 *
 * [run] deletes what is older than the lifetime ([HistoryRecorder.deleteOlderThan]) once the
 * settings are first read, which is at each start of the process, again each time the lifetime
 * changes, so a shorter one applies at once, and after every [PERIOD] the phone is awake while the
 * process lives: the wait is a coroutine delay, which deep sleep pauses. No WorkManager: the history
 * is written only while the process runs, and each start trims it. A deletion that fails is
 * reported by the recorder and tried again at the next.
 */
class HistoryRetention(
    private val recorder: HistoryRecorder,
    private val lifetime: Flow<Duration?>,
    private val scope: CoroutineScope,
) {
    /** Deletes old history until the calling coroutine is cancelled. */
    suspend fun run() {
        lifetime.distinctUntilChanged().collectLatest { kept ->
            // Kept for ever: nothing to delete, until the setting changes.
            if (kept == null) return@collectLatest
            while (true) {
                recorder.deleteOlderThan(kept)
                delay(PERIOD)
            }
        }
    }

    /**
     * *Clear history*, in [scope], which lives as long as the process: once confirmed, a clear is
     * made even if the screen that asked goes away. The result says whether it succeeded.
     */
    fun clear(): Deferred<Boolean> = scope.async { recorder.clear() }

    companion object {
        /**
         * Six hours of the phone being awake. The lifetime is counted in days, so the history is at
         * most about a quarter of a day past it while the phone is in use; each deletion scans the
         * history's two main tables, so a few a day is cheap.
         */
        val PERIOD: Duration = 6.hours
    }
}
