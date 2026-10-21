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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
    private val stats = CacheStats(TableStats(3, 300), TableStats(2, 20), TableStats(1, 1_000), TableStats(4, 400))
    private val fixedClock =
        object : Clock {
            override fun now(): Instant = Instant.parse("2026-10-17T12:00:00Z")
        }

    @After
    fun close() {
        appScope.coroutineContext[Job]?.cancel()
        jdbc.close()
    }

    private class Learned(
        crossfades: Map<String, Duration>,
        private val reset: () -> Boolean = { true },
    ) : SkipLearning {
        override val learnedCrossfades: StateFlow<Map<String, Duration>> = MutableStateFlow(crossfades)

        override suspend fun reset(): Boolean = reset.invoke()
    }

    /** The process's scope, as the app graph gives it: the test's thread, through [runBlocking]. */
    private val appScope = CoroutineScope(SupervisorJob())

    private fun diagnostics(
        readChanges: (Int, Long?) -> TrackChangePage = history::changes,
        readCacheStats: () -> CacheStats = { stats },
        learning: SkipLearning? = null,
    ) = Diagnostics(
        log = DiagnosticsLog(clock = fixedClock),
        readChanges = readChanges,
        readCacheStats = readCacheStats,
        io = EmptyCoroutineContext,
        scope = appScope,
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

    private fun exported(
        diagnostics: Diagnostics,
        expected: ExportResult = ExportResult.WRITTEN,
    ): String {
        val out = ByteArrayOutputStream()
        assertEquals(expected, runBlocking { diagnostics.write { out } })
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
        assertTrue(text, "artist results: 4 entries, 400 bytes" in text)
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

        val text = exported(diagnostics, ExportResult.INCOMPLETE)

        assertTrue(text, DiagnosticsText.NOT_LEARNING in text)
        assertTrue(text, "The caches could not be read." in text)
        assertTrue(text, "The history could not be read." in text)
    }

    @Test
    fun aHistoryPageThatFailsMidwayKeepsWhatWasWrittenAndIsIncomplete() {
        (1..Diagnostics.PAGE_SIZE + 1).forEach { history.changed(change(it)) }
        val diagnostics =
            diagnostics(
                readChanges = { limit, before ->
                    if (before == null) history.changes(limit, before) else throw SQLException("disk I/O error")
                },
            )

        val text = exported(diagnostics, ExportResult.INCOMPLETE)

        assertEquals(Diagnostics.PAGE_SIZE, text.lines().count { it.startsWith("2026-10-17T08:") })
        assertTrue(text, text.trimEnd().endsWith("The history could not be read."))
    }

    @Test
    fun cachesThatCannotBeReadMakeTheExportIncomplete() {
        exported(diagnostics(readCacheStats = { throw SQLException("disk I/O error") }), ExportResult.INCOMPLETE)
    }

    @Test
    @Suppress("ThrowsCount") // Each a provider's failure.
    fun anExportThatCannotBeWrittenIsReportedNotThrown() {
        val failing =
            object : OutputStream() {
                override fun write(b: Int): Unit = throw IOException("No space left on device")
            }
        val failed = { open: () -> OutputStream? -> runBlocking { diagnostics().write(open) } }

        assertEquals(ExportResult.FAILED, failed { failing })
        assertEquals(ExportResult.FAILED, failed { null })
        assertEquals(ExportResult.FAILED, failed { throw SecurityException("Permission withdrawn") })
        // A provider that does not take the mode asked for.
        assertEquals(ExportResult.FAILED, failed { throw IllegalArgumentException("Bad mode: wt") })
        assertEquals(ExportResult.FAILED, failed { throw UnsupportedOperationException("Read-only") })
    }

    @Test
    fun anExportStartedRunsInTheProcessScopeAndSaysHowItEnded() {
        val diagnostics = diagnostics()
        val out = ByteArrayOutputStream()
        assertEquals(ExportState.None, diagnostics.exportState.value)

        runBlocking {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val running =
                Diagnostics(
                    log = DiagnosticsLog(clock = fixedClock),
                    readChanges = history::changes,
                    readCacheStats = { stats },
                    io = EmptyCoroutineContext,
                    scope = scope,
                    version = "1.2.3",
                )
            running.export { out }
            assertEquals(ExportState.Running, running.exportState.value)
            // A second export is not started while one is under way.
            running.export { error("not opened") }
            running.exportState.first { it is ExportState.Ended }
            assertEquals(ExportState.Ended(ExportResult.WRITTEN), running.exportState.value)
            scope.coroutineContext[Job]?.cancel()
        }
        assertTrue(out.size() > 0)
    }

    /**
     * Starts an export in a scope of its own on the test's thread, with [open] given the scope, and
     * waits for the export's job to complete, however it does; then the state it ended in. The
     * scope's handler collects what the export threw ([thrown]).
     */
    private fun exportEnding(
        thrown: MutableList<Throwable> = mutableListOf(),
        before: (CoroutineScope) -> Unit = {},
        open: (CoroutineScope) -> OutputStream?,
    ): ExportState =
        runBlocking {
            val parent = SupervisorJob(coroutineContext[Job])
            val scope = CoroutineScope(coroutineContext + parent + CoroutineExceptionHandler { _, e -> thrown += e })
            val exporting =
                Diagnostics(
                    log = DiagnosticsLog(clock = fixedClock),
                    readChanges = history::changes,
                    readCacheStats = { stats },
                    io = EmptyCoroutineContext,
                    scope = scope,
                    version = "1.2.3",
                )
            before(scope)
            exporting.export { open(scope) }
            parent.children.toList().forEach { it.join() }
            // A job launched in a scope already cancelled is not its child, and completes once the
            // event loop runs it: a bounded number of turns, never a wait on the clock.
            repeat(EVENT_LOOP_TURNS) { if (exporting.exportState.value == ExportState.Running) yield() }
            parent.cancel()
            exporting.exportState.value
        }

    @Test
    fun anExportCancelledMidwayEndsAsFailedNotRunning() {
        val out = ByteArrayOutputStream()
        val state =
            exportEnding { scope ->
                // The process's scope cancelled once the file is open, before anything is read.
                scope.cancel()
                out
            }
        assertEquals(ExportState.Ended(ExportResult.FAILED), state)
    }

    @Test
    fun anExportWhoseScopeIsCancelledBeforeItStartsEndsAsFailed() {
        var opened = false
        val state =
            exportEnding(before = { it.cancel() }) {
                opened = true
                ByteArrayOutputStream()
            }
        assertEquals(ExportState.Ended(ExportResult.FAILED), state)
        assertFalse(opened)
    }

    @Test
    fun anExportEndedByAnErrorEndsAsFailedAndTheErrorStillReachesTheScope() {
        val thrown = mutableListOf<Throwable>()
        val state = exportEnding(thrown) { throw StackOverflowError("a provider's bug") }
        assertEquals(ExportState.Ended(ExportResult.FAILED), state)
        assertEquals(listOf(StackOverflowError::class), thrown.map { it::class })
    }

    @Test
    fun aResetThatFailsIsReportedNotThrown() {
        val reported = Learned(mapOf("p" to 1.seconds)) { false }
        val thrown = Learned(mapOf("p" to 1.seconds)) { error("database is locked") }

        assertTrue(runBlocking { diagnostics(learning = Learned(emptyMap())).resetLearning() })
        assertFalse(runBlocking { diagnostics(learning = reported).resetLearning() })
        assertFalse(runBlocking { diagnostics(learning = thrown).resetLearning() })
        assertFalse(runBlocking { diagnostics().resetLearning() })
    }

    private companion object {
        const val EVENT_LOOP_TURNS = 10
    }
}
