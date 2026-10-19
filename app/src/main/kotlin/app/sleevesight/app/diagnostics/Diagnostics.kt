package app.sleevesight.app.diagnostics

import android.database.SQLException
import android.util.Log
import app.sleevesight.store.CacheStats
import app.sleevesight.store.TrackChangePage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

/**
 * What the diagnostics screen shows (`design/design.md` and `design/architecture.md`,
 * *Diagnostics*; D15): the pipeline's events kept in memory ([log]), the track changes recorded with
 * their features, a page at a time ([changes]), the caches' sizes ([cacheStats]), and what skip
 * detection has learned, with its reset ([skipLearning], [resetLearning]; null until skip detection
 * supplies it, #53). [export] writes all of it to a file the user chose; nothing else of it is
 * written anywhere.
 *
 * The history and the caches are read on [io]. A read that fails is logged by its type and shown as
 * failed, never thrown. [readChanges] is `SessionHistory.changes`, [readCacheStats]
 * `Caches.stats`, which opens the cache database if nothing has yet: both are read only once the
 * screen asks, which is after the first run is acknowledged.
 *
 * An export runs in [scope], the process's, not the screen's: a rotation, a press on back or the
 * activity recreated does not cut it short, and [exportState] tells the screen, whichever instance
 * is showing, how it is going and how the last one ended.
 */
@Suppress("LongParameterList") // What the screen reads, each its own; the clock is a test seam.
class Diagnostics(
    val log: DiagnosticsLog,
    private val readChanges: (limit: Int, before: Long?) -> TrackChangePage,
    private val readCacheStats: () -> CacheStats,
    private val io: CoroutineContext,
    private val scope: CoroutineScope,
    private val version: String,
    val skipLearning: SkipLearning? = null,
    private val clock: Clock = Clock.System,
) {
    private val exporting = MutableStateFlow<ExportState>(ExportState.None)

    /** The export under way, or how the last one ended. */
    val exportState: StateFlow<ExportState> = exporting.asStateFlow()

    /** The page of track changes older than [before] (the previous page's `next`), or the latest. */
    suspend fun changes(before: Long? = null): PageResult =
        withContext(io) {
            try {
                PageResult.Read(readChanges(PAGE_SIZE, before))
            } catch (e: SQLException) {
                Log.w(TAG, "The track changes could not be read: ${e::class.simpleName}")
                PageResult.Failed
            }
        }

    /** The caches' sizes, or null if they could not be read. */
    suspend fun cacheStats(): CacheStats? =
        withContext(io) {
            try {
                readCacheStats()
            } catch (e: SQLException) {
                Log.w(TAG, "The cache sizes could not be read: ${e::class.simpleName}")
                null
            }
        }

    /**
     * Starts writing everything the screen shows, every track change included, as text to the stream
     * [open] gives (a file the user chose; null if it could not be opened), in the process's scope;
     * [exportState] follows it. Nothing starts while an export is under way. [open] is kept until the
     * export ends, so it must not hold an activity.
     *
     * The state ends however the export does, from the job's completion, not from its body: an
     * export cancelled (the scope with it, even before it started) or ended by an [Error] that
     * [write] does not catch is [ExportResult.FAILED], so the screen never shows it running for ever
     * and a next export can start. The [Error] itself still goes to the scope.
     */
    fun export(open: () -> OutputStream?) {
        val before = exporting.getAndUpdate { if (it == ExportState.Running) it else ExportState.Running }
        if (before == ExportState.Running) return
        var result: ExportResult? = null
        scope.launch { result = write(open) }.invokeOnCompletion { cause ->
            if (cause != null) Log.w(TAG, "The diagnostics export ended early: ${cause::class.simpleName}")
            exporting.value = ExportState.Ended(result ?: ExportResult.FAILED)
        }
    }

    /**
     * Writes the export to the stream [open] gives, and says how it went. A part that could not be
     * read (the caches, a page of the history) is said so in the file, which is kept, and makes the
     * result [ExportResult.INCOMPLETE]. A failure is logged by its type, never with its message,
     * which may name a path. A provider's `openOutputStream` runs another app's code, so any runtime
     * exception from it is a failed export, not a crash.
     */
    @Suppress("TooGenericExceptionCaught") // Another app's code, as above.
    internal suspend fun write(open: () -> OutputStream?): ExportResult =
        withContext(io) {
            try {
                val stream = open() ?: return@withContext ExportResult.FAILED
                val complete =
                    stream.bufferedWriter().use { out ->
                        DiagnosticsText.export(
                            out = out,
                            version = version,
                            exportedAt = clock.now(),
                            learned = skipLearning?.learnedCrossfades?.value,
                            caches = cacheStats(),
                            events = log.entries.value,
                            changes = { before -> changes(before) },
                        )
                    }
                if (complete) ExportResult.WRITTEN else ExportResult.INCOMPLETE
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "The diagnostics could not be exported: ${e::class.simpleName}")
                ExportResult.FAILED
            } catch (e: RuntimeException) {
                Log.w(TAG, "The diagnostics could not be exported: ${e::class.simpleName}")
                ExportResult.FAILED
            }
        }

    /**
     * Forgets what skip detection has learned; false if it could not, or nothing supplies it. A
     * failure the learning throws rather than reports is logged by its type, never thrown on.
     */
    @Suppress("TooGenericExceptionCaught") // The learning is another component's: any failure is reported.
    suspend fun resetLearning(): Boolean {
        val learning = skipLearning ?: return false
        return try {
            learning.reset()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "What skip detection learned could not be reset: ${e::class.simpleName}")
            false
        }
    }

    companion object {
        /** The track changes read at a time: a screenful or two, each a line with its states. */
        const val PAGE_SIZE: Int = 50

        private const val TAG = "Sleevesight"
    }
}

/** A page of track changes, or the finding that the history could not be read. */
sealed interface PageResult {
    data class Read(
        val page: TrackChangePage,
    ) : PageResult

    data object Failed : PageResult
}

/** How an export ended. */
enum class ExportResult {
    /** Everything was written. */
    WRITTEN,

    /** The file was written, but part of what it holds (the caches, the history) could not be read. */
    INCOMPLETE,

    /** The file could not be opened or written; what was written before the failure is kept. */
    FAILED,
}

/** Whether an export is under way, and how the last one ended. */
sealed interface ExportState {
    /** None since the process started. */
    data object None : ExportState

    data object Running : ExportState

    data class Ended(
        val result: ExportResult,
    ) : ExportState
}
