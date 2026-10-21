package app.sleevesight.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.Caches
import app.sleevesight.store.TableStats
import app.sleevesight.store.db.CacheDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A `cache.db` a newer version left is rebuilt, not a failure at every query (#238). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RebuiltOnDowngradeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun delete() {
        context.deleteDatabase(NAME)
    }

    @Test
    fun aCacheFromANewerVersionIsDroppedAndCreatedAfresh() {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(NAME), null).use { db ->
            db.execSQL("CREATE TABLE from_the_future (x INTEGER)")
            db.version = CacheDatabase.Schema.version.toInt() + 1
        }

        val driver =
            AndroidSqliteDriver(
                CacheDatabase.Schema,
                context,
                NAME,
                callback = RebuiltOnDowngrade(CacheDatabase.Schema),
            )
        try {
            val caches = Caches(driver, { Settings() }, Normalizer(JvmAccentFolder))
            assertEquals(TableStats(0, 0), caches.stats().artistResults)
        } finally {
            driver.close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(NAME).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(CacheDatabase.Schema.version.toInt(), db.version)
            db.rawQuery("SELECT name FROM sqlite_master WHERE name = 'from_the_future'", null).use {
                assertFalse("the newer version's table is gone", it.moveToNext())
            }
        }
    }

    private companion object {
        const val NAME = "downgraded-cache.db"
    }
}
