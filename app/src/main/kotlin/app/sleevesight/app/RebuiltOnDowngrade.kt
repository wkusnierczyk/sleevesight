package app.sleevesight.app

import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * How `cache.db` opens: created or migrated by [schema], as the driver's own callback does, and, on
 * a database a newer version of the app left (an older build installed over a newer one, which
 * only a test phone sees, since Play forbids downgrades), dropped and created afresh rather than
 * failing at every query, as the platform's default does. The caches can be rebuilt, at the cost of
 * the lookups and paid calls again; the history cannot, and its database has no such callback.
 */
class RebuiltOnDowngrade(
    schema: SqlSchema<QueryResult.Value<Unit>>,
) : AndroidSqliteDriver.Callback(schema) {
    override fun onDowngrade(
        db: SupportSQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        val tables = mutableListOf<String>()
        db
            .query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name <> 'android_metadata'",
            ).use { cursor -> while (cursor.moveToNext()) tables += cursor.getString(0) }
        // A table's indexes and triggers go with it.
        tables.forEach { db.execSQL("DROP TABLE IF EXISTS \"${it.replace("\"", "\"\"")}\"") }
        onCreate(db)
    }
}
