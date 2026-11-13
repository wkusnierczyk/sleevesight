package app.sleevesight.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

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
 */
object Wiping {
    /** The statement that turns on secure deletion for the connection that runs it. */
    const val SECURE_DELETE = "PRAGMA secure_delete = ON"

    private const val CHECKPOINT = "PRAGMA wal_checkpoint(TRUNCATE)"

    /** Runs [SECURE_DELETE] on [driver]: inside a transaction, on that transaction's connection. */
    internal fun secureDelete(driver: SqlDriver) = pragma(driver, SECURE_DELETE)

    /** Checkpoints [driver]'s write-ahead log into the database and truncates it; outside a transaction. */
    internal fun checkpoint(driver: SqlDriver) = pragma(driver, CHECKPOINT)

    // Both return a row; the Android driver runs a statement only as its cursor is read.
    private fun pragma(
        driver: SqlDriver,
        sql: String,
    ) {
        driver.executeQuery(null, sql, { cursor -> QueryResult.Value(cursor.next().value) }, 0)
    }
}
