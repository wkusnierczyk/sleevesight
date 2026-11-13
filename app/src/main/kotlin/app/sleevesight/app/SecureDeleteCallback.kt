package app.sleevesight.app

import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.sleevesight.store.Wiping

/**
 * How `history.db` and `cache.db` open: created or migrated by [schema], as the driver's own
 * callback does, with secure deletion turned on as the database is configured ([Wiping], #1044), so
 * every delete on it overwrites what it frees with zeros. Android configures each database once,
 * on the connection that writes; the deletions that must wipe set it again in their own
 * transaction besides.
 */
open class SecureDeleteCallback(
    schema: SqlSchema<QueryResult.Value<Unit>>,
) : AndroidSqliteDriver.Callback(schema) {
    override fun onConfigure(db: SupportSQLiteDatabase) {
        super.onConfigure(db)
        // A pragma that returns a row runs as its cursor is read.
        db.query(Wiping.SECURE_DELETE).use { it.moveToFirst() }
    }
}
