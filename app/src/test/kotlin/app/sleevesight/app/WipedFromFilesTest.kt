package app.sleevesight.app

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.store.Caches
import app.sleevesight.store.SessionHistory
import app.sleevesight.store.SynthesisKey
import app.sleevesight.store.db.CacheDatabase
import app.sleevesight.store.history.HistoryDatabase
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * What *Clear history* and the retention delete is gone from the database's files too (#1044), not
 * only from its tables: SQLite leaves a deleted row's bytes in the page it was on, and the
 * write-ahead log keeps the pages written before, until they are overwritten. Over the SQLite JDBC
 * driver on real files, in write-ahead-log mode (as the phone's databases are) and in rollback-journal
 * mode; each test first checks the text is in the files, so its absence after is not by chance.
 * Here rather than in `:store`, whose tests keep to Multiplatform-ready imports (D2), as files need
 * `java.io`. The Android driver's part is `SecureDeleteCallbackTest`. Under Robolectric, as the
 * other tests here that open the JDBC driver: one that loads it outside the sandbox leaves it
 * unregistered for theirs.
 */
@RunWith(RobolectricTestRunner::class)
class WipedFromFilesTest {
    private val dir: File = Files.createTempDirectory("wiped").toFile()
    private val drivers = mutableListOf<SqlDriver>()

    @After
    fun cleanUp() {
        drivers.forEach { it.close() }
        dir.deleteRecursively()
    }

    private fun open(
        name: String,
        wal: Boolean,
    ): Pair<File, SqlDriver> {
        val file = File(dir, name)
        val properties = Properties().apply { if (wal) put("journal_mode", "WAL") }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.path}", properties)
        drivers += driver
        return file to driver
    }

    // Whether [text] is anywhere in the database [file], its write-ahead log or its journal.
    private fun inFiles(
        file: File,
        text: String,
    ): Boolean {
        val needle = text.toByteArray()
        return listOf(file, File("${file.path}-wal"), File("${file.path}-journal"))
            .filter { it.exists() }
            .any { contains(it.readBytes(), needle) }
    }

    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean =
        (0..haystack.size - needle.size).any { start ->
            needle.indices.all { haystack[start + it] == needle[it] }
        }

    private fun history(wal: Boolean): Pair<File, SessionHistory> {
        val (file, driver) = open("history.db", wal)
        HistoryDatabase.Schema.create(driver)
        return file to SessionHistory(driver)
    }

    private fun SessionHistory.play(
        title: String,
        at: Instant,
    ) = started(NowPlaying(title = title, artists = "Glen Campbell", player = SPOTIFY), at, 30.minutes) { false }

    @Test
    fun `clear history leaves no deleted title in the history's database or its write-ahead log`() {
        val (file, history) = history(wal = true)
        history.play(LINEMAN, START)
        assertTrue("the title is written to the files at first", inFiles(file, LINEMAN))

        history.clear()

        assertFalse("the title is wiped from the files", inFiles(file, LINEMAN))
    }

    @Test
    fun `clear history leaves no deleted title in a rollback-journal database`() {
        val (file, history) = history(wal = false)
        history.play(LINEMAN, START)
        assertTrue("the title is written to the file at first", inFiles(file, LINEMAN))

        history.clear()

        assertFalse("the title is wiped from the file", inFiles(file, LINEMAN))
    }

    @Test
    fun `the retention leaves no deleted title in the history's files`() {
        val (file, history) = history(wal = true)
        history.play(LINEMAN, START)
        history.play(GALVESTON, START + 10.days)
        assertTrue("the title is written to the files at first", inFiles(file, LINEMAN))

        history.deleteOlderThan(1.days, START + 10.days)

        assertFalse("the old title is wiped from the files", inFiles(file, LINEMAN))
        assertTrue("the title kept is still there", inFiles(file, GALVESTON))
    }

    private fun caches(
        wal: Boolean,
        settings: Settings = Settings(),
    ): Pair<File, Caches> {
        val (file, driver) = open("cache.db", wal)
        CacheDatabase.Schema.create(driver)
        return file to Caches(driver, { settings }, Normalizer(JvmAccentFolder), FixedClock(START + 10.days))
    }

    @Test
    fun `clear history leaves no deleted insight in the caches' files`() {
        val (file, caches) = caches(wal = true)
        caches.synthesis.put(KEY, INSIGHT, START)
        assertTrue("the insight is written to the files at first", inFiles(file, INSIGHT))

        caches.clearAll()

        assertFalse("the insight is wiped from the files", inFiles(file, INSIGHT))
    }

    @Test
    fun `the caches' retention leaves no expired insight in their files`() {
        val (file, caches) = caches(wal = true, settings = Settings(historyLifetime = 1.days))
        caches.synthesis.put(KEY, INSIGHT, START)
        assertTrue("the insight is written to the files at first", inFiles(file, INSIGHT))

        caches.purgeExpired()

        assertFalse("the expired insight is wiped from the files", inFiles(file, INSIGHT))
    }

    private class FixedClock(
        private val at: Instant,
    ) : Clock {
        override fun now(): Instant = at
    }

    private companion object {
        const val SPOTIFY = "com.spotify.music"
        const val LINEMAN = "Wichita Lineman"
        const val GALVESTON = "Galveston"
        const val INSIGHT = "A lineman's song that Jimmy Webb wrote in an afternoon"
        val START: Instant = Instant.parse("2026-10-16T08:00:00Z")
        val KEY = SynthesisKey(CardKind.INSIGHT, "k", "fp", 1, "style", ProviderId.ANTHROPIC, "model", false, null)
    }
}
