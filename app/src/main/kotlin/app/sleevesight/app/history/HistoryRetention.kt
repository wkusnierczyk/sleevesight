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
 * queue, so they are ordered with the history's writes. *Clear history* then runs [forget], which
 * deletes everything else kept about the tracks played (#547: the caches, and what is held in
 * memory about them), and returns whether it did.
 *
 * [run] deletes what is older than the lifetime ([HistoryRecorder.deleteOlderThan]) once the
 * settings are first read, which is at each start of the process, again each time the lifetime
 * changes, so a shorter one applies at once, and after every [PERIOD] the phone is awake while the
 * process lives: the wait is a coroutine delay, which deep sleep pauses. No WorkManager: the history
 * is written only while the process runs, and each start trims it. A deletion reads the lifetime
 * when it runs, not when it was queued, so one waiting behind other writes when the lifetime is made
 * longer, or *Forever*, deletes by the new one. One that fails is reported by the recorder and
 * tried again at the next; the recorder skips them while the clock has moved.
 *
 * After each deletion, [trim] runs: the caches' purge, which deletes the insights and genre
 * reasons written longer ago than the lifetime (#643), so they go with the history, at once when the
 * lifetime is shortened and on the same [PERIOD], not only at the next start.
 */
class HistoryRetention(
    private val recorder: HistoryRecorder,
    private val lifetime: Flow<Duration?>,
    private val scope: CoroutineScope,
    // Before [forget], which a trailing lambda names.
    private val trim: suspend () -> Unit = {},
    private val forget: suspend () -> Boolean = { true },
) {
    // The lifetime now, for the deletions already queued: they run on the I/O dispatcher.
    @Volatile
    private var current: Duration? = null

    /** Deletes old history until the calling coroutine is cancelled. */
    suspend fun run() {
        lifetime.distinctUntilChanged().collectLatest { kept ->
            current = kept
            // Kept for ever: nothing to delete, until the setting changes.
            if (kept == null) return@collectLatest
            while (true) {
                recorder.deleteOlderThan { current }
                trim()
                delay(PERIOD)
            }
        }
    }

    /**
     * *Clear history*, in [scope], which lives as long as the process: once confirmed, a clear is
     * made even if the screen that asked goes away. The history is cleared, then [forget] runs,
     * even if the history's clear failed, so as much as can be is deleted. The result says whether
     * both succeeded.
     */
    fun clear(): Deferred<Boolean> =
        scope.async {
            val history = recorder.clear()
            val rest = forget()
            history && rest
        }

    companion object {
        /**
         * Six hours of the phone being awake. The lifetime is counted in days, so while the process
         * runs and the phone is awake, the history is at most about a quarter of a day past it (and
         * past it by however long the phone slept, or the process was not running, otherwise);
         * each deletion scans the history's two main tables, so a few a day is cheap.
         */
        val PERIOD: Duration = 6.hours
    }
}
