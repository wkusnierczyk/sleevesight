package app.sleevesight.app.history

import android.database.SQLException
import android.util.Log
import app.sleevesight.store.SessionHistory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Keeps the session history and the track changes ([SessionHistory]) for the user's *Keep history
 * for* ([lifetime]; null keeps them for ever), and clears them on *Clear history*.
 *
 * [run] deletes what is older than the lifetime ([SessionHistory.deleteBefore]) once the settings
 * are first read, which is at the app's start, again each time the lifetime changes, so a shorter
 * one applies at once, and every [PERIOD] while the process lives. No WorkManager: the history is
 * written only while the process runs, so it needs trimming only then. Each deletion runs on [io];
 * one that fails is reported to [warn] and tried again at the next, never thrown.
 */
class HistoryRetention(
    private val history: SessionHistory,
    private val lifetime: Flow<Duration?>,
    private val io: CoroutineContext,
    private val clock: Clock = Clock.System,
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
) {
    /** Deletes old history until the calling coroutine is cancelled. */
    suspend fun run() {
        lifetime.distinctUntilChanged().collectLatest { kept ->
            // Kept for ever: nothing to delete, until the setting changes.
            if (kept == null) return@collectLatest
            while (true) {
                try {
                    withContext(io) { history.deleteBefore(clock.now() - kept) }
                } catch (_: SQLException) {
                    // No exception text: a database message may name a path or a track.
                    warn("Could not delete old entries from the session history")
                }
                delay(PERIOD)
            }
        }
    }

    /**
     * *Clear history*: deletes the session history and the track changes together. Returns whether
     * it succeeded; a failure is not thrown, and its text is not kept, as it may name a path.
     */
    suspend fun clear(): Boolean =
        withContext(io) {
            try {
                history.clear()
                true
            } catch (_: SQLException) {
                warn("Could not clear the session history")
                false
            }
        }

    companion object {
        private const val TAG = "Sleevesight"

        /**
         * Six hours. The lifetime is counted in days, so the history is never more than a quarter of
         * a day past it; each deletion scans the history's two main tables, so a few a day is cheap.
         */
        val PERIOD: Duration = 6.hours
    }
}
