package app.sleevesight.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.coroutines.cancellation.CancellationException

/**
 * Deleted rows wiped from the database's files, not only from its tables (#1044). SQLite leaves a
 * deleted row's bytes in the page it was on until the page is reused, and the write-ahead log keeps
 * the pages written before until it is checkpointed and overwritten; so a title cleared from the
 * history could still be read from `history.db` or `history.db-wal`.
 *
 * - [SECURE_DELETE] makes SQLite overwrite what a delete frees with zeros. It holds for one
 *   connection: the app sets it as each database opens (`SecureDeleteCallback` in `:app`), and the
 *   deletions that must wipe ([SessionHistory.clear] and [SessionHistory.deleteOlderThan],
 *   [Caches.clearAll] and [Caches.purgeExpired]) set it again in their own transaction, so they
 *   wipe on whatever connection runs them.
 * - [checkpoint], after those deletions commit: the write-ahead log's pages are copied into the
 *   database, the zeroed ones over the old, and the log is truncated to nothing. Without a
 *   write-ahead log (a rollback journal) it does nothing. Blocking, like every call here: the
 *   callers are off the main thread.
 *
 * A reader on another connection that began before the deletion holds the log's older pages, and
 * the checkpoint cannot truncate past it: SQLite reports it busy, after waiting in the driver's
 * busy handler, rather than failing. [checkpoint] tries [ATTEMPTS] times, each waiting there, and
 * then says so; the deletion stands (the rows are gone from the tables), and the callers keep
 * [Unwiped.pending] set until a later checkpoint succeeds (as they do when it fails outright),
 * which runs after every clear and every retention run (each start among them). The app's
 * Android driver keeps one connection per
 * database, so no such reader is expected there; the JDBC driver opens one per thread.
 */
object Wiping {
    /** The statement that turns on secure deletion for the connection that runs it. */
    const val SECURE_DELETE = "PRAGMA secure_delete = ON"

    private const val CHECKPOINT = "PRAGMA wal_checkpoint(TRUNCATE)"

    /** How many times [checkpoint] tries while the log is busy, each after the busy handler's wait. */
    private const val ATTEMPTS = 3

    /** Runs [SECURE_DELETE] on [driver]: inside a transaction, on that transaction's connection. */
    internal fun secureDelete(driver: SqlDriver) = pragma(driver, SECURE_DELETE)

    /**
     * Checkpoints [driver]'s write-ahead log into the database and truncates it, outside a
     * transaction. Returns whether it did; false if a reader kept it busy for [ATTEMPTS] tries.
     */
    internal fun checkpoint(driver: SqlDriver): Boolean =
        (1..ATTEMPTS).any {
            // Its first column, `busy`, is 1 when the checkpoint could not finish.
            driver
                .executeQuery(null, CHECKPOINT, { QueryResult.Value(it.next().value && it.getLong(0) == 0L) }, 0)
                .value
        }

    // Returns a row; the Android driver runs a statement only as its cursor is read.
    private fun pragma(
        driver: SqlDriver,
        sql: String,
    ) {
        driver.executeQuery(null, sql, { cursor -> QueryResult.Value(cursor.next().value) }, 0)
    }

    /**
     * Whether a deletion's write-ahead log is still to be checkpointed ([pending]): set when
     * [checkpoint] stays busy, cleared when a later one succeeds. Shared by a store's threads.
     */
    class Unwiped {
        @Volatile
        var pending: Boolean = false
            private set

        /**
         * Checkpoints [driver] after a deletion, and records whether the log is still unwiped. A
         * checkpoint that fails (an I/O error, a lock) never fails the deletion, which has
         * committed: the log is left pending, as for a busy one.
         */
        internal fun checkpoint(driver: SqlDriver) {
            pending =
                try {
                    !Wiping.checkpoint(driver)
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    // The driver's database error, whatever its type; the next deletion tries again.
                    @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
                ) {
                    true
                }
        }
    }
}
