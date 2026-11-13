package app.sleevesight.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.store.SessionHistory
import app.sleevesight.store.history.HistoryDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * *Clear history* and the retention, through the Android driver on a database opened with
 * [SecureDeleteCallback], run their pragmas without error and leave no deleted title in the files
 * (#1044). Robolectric's SQLite, like Android's, is built with secure deletion on by default and
 * keeps its journal in memory, so this passes without the change too: it guards that the pragmas
 * run on the Android driver (the callback's, the transaction's and the checkpoint), while the
 * wiping itself is tested in `WipedFromFilesTest`, over SQLite without that default.
 */
@RunWith(RobolectricTestRunner::class)
class SecureDeleteCallbackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val file: File = context.getDatabasePath(NAME)
    private val driver =
        AndroidSqliteDriver(
            HistoryDatabase.Schema,
            context,
            NAME,
            callback = SecureDeleteCallback(HistoryDatabase.Schema),
        )

    @After
    fun delete() {
        driver.close()
        context.deleteDatabase(NAME)
    }

    @Test
    fun clearHistoryAndTheRetentionRunOnTheAndroidDriverAndLeaveNoDeletedTitle() {
        val history = SessionHistory(driver)
        val track = NowPlaying(title = TITLE, artists = "Glen Campbell", player = "com.spotify.music")
        history.started(track, Instant.parse("2026-10-16T08:00:00Z"), 30.minutes) { false }
        assertTrue("the title is written to the files at first", inFiles())

        history.deleteOlderThan(1.days, Instant.parse("2026-10-26T08:00:00Z"))
        history.clear()

        assertFalse("the title is wiped from the files", inFiles())
        assertEquals(emptyList<Any>(), history.latestSession())
    }

    // Whether [TITLE] is anywhere in the database, its write-ahead log or its journal.
    private fun inFiles(): Boolean {
        val needle = TITLE.toByteArray()
        return listOf(file, File("${file.path}-wal"), File("${file.path}-journal"))
            .filter { it.exists() }
            .any { candidate ->
                val bytes = candidate.readBytes()
                (0..bytes.size - needle.size).any { start -> needle.indices.all { bytes[start + it] == needle[it] } }
            }
    }

    private companion object {
        const val NAME = "secure-delete-history.db"
        const val TITLE = "Wichita Lineman"
    }
}
