package app.sleevesight.app.diagnostics

import android.database.SQLException
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.store.CacheStats
import app.sleevesight.store.PlaybackStateRecord
import app.sleevesight.store.SessionHistory
import app.sleevesight.store.TableStats
import app.sleevesight.store.TrackChangePage
import app.sleevesight.store.TrackChangeRecord
import app.sleevesight.store.TrackRef
import app.sleevesight.store.history.HistoryDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What the diagnostics screen reads, and its export (#54). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DiagnosticsTest {
    private val jdbc = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HistoryDatabase.Schema.create(it) }
    private val history = SessionHistory(jdbc)
    private val start = Instant.parse("2026-10-17T08:00:00Z")
    private val stats = CacheStats(TableStats(3, 300), TableStats(2, 20), TableStats(1, 1_000))
    private val fixedClock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-10-17T12:00:00Z")
        }

    @After
    fun close() = jdbc.close()

    private class Learned(
        crossfades: Map<String, Duration>,
    ) : SkipLearning {
        override val learnedCrossfades: StateFlow<Map<String, Duration>> = MutableStateFlow(crossfades)

        override suspend fun reset() = Unit
    }

    private fun diagnostics(
        readChanges: (Int, Long?) -> TrackChangePage = history::changes,
        readCacheStats: () -> CacheStats = { stats },
        learning: SkipLearning? = null,
    ) = Diagnostics(
        log = DiagnosticsLog(clock = fixedClock),
        readChanges = readChanges,
        readCacheStats = readCacheStats,
        io = EmptyCoroutineContext,
        version = "1.2.3",
        skipLearning = learning,
        clock = fixedClock,
    )

    private fun change(n: Int) =
        TrackChangeRecord(
            player = "com.spotify.music",
            changedAt = start + n.seconds,
            from = TrackRef("Song $n", "spotify:track:$n"),
            to = TrackRef("Song ${n + 1}", null),
            timeLeft = 2_000.milliseconds,
            states =
                listOf(
                    PlaybackStateRecord(1_200.milliseconds, "PLAYING", 183_400.milliseconds, 1_190.milliseconds, 1f),
                ),
            sinceLastAction = null,
            learnedCrossfade = null,
        )

    private fun exported(diagnostics: Diagnostics): String {
        val out = ByteArrayOutputStream()
        assertTrue(runBlocking { diagnostics.export { out } })
        return out.toString(Charsets.UTF_8)
    }

    @Test
    fun readsTheTrackChangesAPageAtATimeNewestFirst() {
        (1..Diagnostics.PAGE_SIZE + 2).forEach { history.changed(change(it)) }
        val diagnostics = diagnostics()

        val first = runBlocking { diagnostics.changes() } as PageResult.Read
        val second = runBlocking { diagnostics.changes(first.page.next) } as PageResult.Read

        assertEquals(Diagnostics.PAGE_SIZE, first.page.changes.size)
        assertEquals(
            "Song ${Diagnostics.PAGE_SIZE + 3}",
            first.page.changes
                .first()
                .to.title,
        )
        assertEquals(listOf("Song 3", "Song 2"), second.page.changes.map { it.to.title })
        assertEquals(null, second.page.next)
    }

    @Test
    fun aHistoryThatCannotBeReadIsAFailedPageNotAThrow() {
        val diagnostics = diagnostics(readChanges = { _, _ -> throw SQLException("disk I/O error") })

        assertEquals(PageResult.Failed, runBlocking { diagnostics.changes() })
    }

    @Test
    fun cachesThatCannotBeReadAreNoSizes() {
        val diagnostics = diagnostics(readCacheStats = { throw SQLException("disk I/O error") })

        assertEquals(null, runBlocking { diagnostics.cacheStats() })
    }

    @Test
    fun theExportHoldsEverythingTheScreenShowsEveryTrackChangeIncluded() {
        (1..Diagnostics.PAGE_SIZE + 1).forEach { history.changed(change(it)) }
        val diagnostics = diagnostics(learning = Learned(mapOf("com.spotify.music" to 2_000.milliseconds)))
        diagnostics.log.record(
            PipelineEvent.SourceLanded(
                "song",
                SourceId.MUSICBRAINZ,
                Outcome.SongArticle,
                3,
                0,
                0,
                312.milliseconds,
                false,
            ),
        )

        val text = exported(diagnostics)

        assertTrue(text, text.startsWith("Sleevesight diagnostics, version 1.2.3, exported 2026-10-17T12:00:00Z\n"))
        assertTrue(text, "com.spotify.music: learned crossfade 2000 ms" in text)
        assertTrue(text, "retrieval: 3 entries, 300 bytes" in text)
        assertTrue(text, "2026-10-17T12:00:00Z source     song: MB song_article, 3 cited" in text)
        val changes = text.lines().filter { it.startsWith("2026-10-17T08:") }
        assertEquals(Diagnostics.PAGE_SIZE + 1, changes.size)
        assertEquals(
            "2026-10-17T08:00:51Z com.spotify.music: \"Song 51\" (spotify:track:51) -> \"Song 52\"; " +
                "time left 2000 ms, since last action -, learned crossfade -, 1 states",
            changes.first(),
        )
        assertTrue(text, "  -1200 ms PLAYING, position 183400 ms (taken 1190 ms before), speed 1.0" in text)
    }

    @Test
    fun theExportSaysWhatCouldNotBeReadOrIsNotLearned() {
        val diagnostics =
            diagnostics(
                readChanges = { _, _ -> throw SQLException("disk I/O error") },
                readCacheStats = { throw SQLException("disk I/O error") },
            )

        val text = exported(diagnostics)

        assertTrue(text, DiagnosticsText.NOT_LEARNING in text)
        assertTrue(text, "The caches could not be read." in text)
        assertTrue(text, "The history could not be read." in text)
    }

    @Test
    fun anExportThatCannotBeWrittenIsReportedNotThrown() {
        val failing =
            object : OutputStream() {
                override fun write(b: Int): Unit = throw IOException("No space left on device")
            }

        assertFalse(runBlocking { diagnostics().export { failing } })
        assertFalse(runBlocking { diagnostics().export { null } })
        assertFalse(runBlocking { diagnostics().export { throw SecurityException("Permission withdrawn") } })
    }
}
