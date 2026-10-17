package app.sleevesight.app.diagnostics

import android.database.SQLException
import android.util.Log
import app.sleevesight.store.CacheStats
import app.sleevesight.store.TrackChangePage
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * What the diagnostics screen shows (`design/design.md` and `design/architecture.md`,
 * *Diagnostics*; D15): the pipeline's events kept in memory ([log]), the track changes recorded with
 * their features, a page at a time ([changes]), the caches' sizes ([cacheStats]), and what skip
 * detection has learned, with its reset ([skipLearning]; null until skip detection supplies it,
 * #53). [export] writes all of it to a file the user chose; nothing else of it is written anywhere.
 *
 * The history and the caches are read on [io]. A read that fails is logged by its type and shown as
 * failed, never thrown. [readChanges] is `SessionHistory.changes`, [readCacheStats]
 * `Caches.stats`, which opens the cache database if nothing has yet: both are read only once the
 * screen asks, which is after the first run is acknowledged.
 */
@Suppress("LongParameterList") // What the screen reads, each its own; the clock is a test seam.
class Diagnostics(
    val log: DiagnosticsLog,
    private val readChanges: (limit: Int, before: Long?) -> TrackChangePage,
    private val readCacheStats: () -> CacheStats,
    private val io: CoroutineContext,
    private val version: String,
    val skipLearning: SkipLearning? = null,
    private val clock: Clock = Clock.System,
) {
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
     * Writes everything the screen shows, every track change recorded included, as text to the
     * stream [open] gives (a file the user chose; null if it could not be opened). Returns whether
     * it was written; a failure is logged by its type, never with its message, which may name a path.
     */
    suspend fun export(open: () -> OutputStream?): Boolean =
        withContext(io) {
            try {
                val stream = open() ?: return@withContext false
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
                true
            } catch (e: IOException) {
                Log.w(TAG, "The diagnostics could not be exported: ${e::class.simpleName}")
                false
            } catch (e: SecurityException) {
                // The document provider withdrew access to the file chosen.
                Log.w(TAG, "The diagnostics could not be exported: ${e::class.simpleName}")
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
