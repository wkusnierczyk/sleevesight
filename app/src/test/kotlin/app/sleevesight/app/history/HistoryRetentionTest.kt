package app.sleevesight.app.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.SessionHistory
import app.sleevesight.store.TrackChangeRecord
import app.sleevesight.store.TrackRef
import app.sleevesight.store.history.HistoryDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** *Keep history for* and *Clear history*, in virtual time; the wall clock reads [base] plus it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
internal class HistoryRetentionTest {
    private val jdbc = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HistoryDatabase.Schema.create(it) }
    private val driver = HistoryRecorderFixture.FailingDriver(jdbc)
    private val history = SessionHistory(driver)
    private val lifetime = MutableStateFlow<Duration?>(Settings.DEFAULT_HISTORY_LIFETIME)
    private val warnings = mutableListOf<String>()
    private val base = Instant.parse("2026-10-16T08:00:00Z")

    @After
    fun close() = jdbc.close()

    private fun TestScope.retention() =
        HistoryRetention(
            history,
            lifetime,
            io = EmptyCoroutineContext,
            clock =
                object : Clock {
                    override fun now(): Instant = base + testScheduler.currentTime.milliseconds
                },
            warn = { warnings += it },
        )

    /** A track and a change into it, both at [at]. */
    private fun played(
        title: String,
        at: Instant,
    ) {
        history.started(NowPlaying(title = title, artists = "Gordon Lightfoot", player = SPOTIFY), at, GAP) { false }
        history.changed(TrackChangeRecord(SPOTIFY, at, null, TrackRef(title, null), null, emptyList(), null, null))
    }

    private fun titles(): List<String> = history.changes(limit = 100).changes.map { it.to.title }

    /** The titles in the history, oldest first: each track [played] is a session of its own. */
    private fun rows(): List<String> = (1L..MAX_SESSIONS).flatMap(history::session).map { it.track.title }

    @Test
    fun whatIsOlderThanTheLifetimeGoesAtStartAndThenEverySixHours() =
        runTest {
            played("Old", base - 91.days)
            // Past 90 days three hours from now, so at the next purge but not this one.
            played("Turning", base - 90.days + 3.hours)
            played("New", base - 1.days)
            val job = launch { retention().run() }
            runCurrent()

            assertEquals(listOf("Turning", "New"), rows())
            assertEquals(listOf("New", "Turning"), titles())

            advanceTimeBy(HistoryRetention.PERIOD - 1.milliseconds)
            runCurrent()
            assertEquals(listOf("Turning", "New"), rows())
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(listOf("New"), rows())
            assertEquals(listOf("New"), titles())
            job.cancel()
        }

    @Test
    fun foreverDeletesNothingAndAShorterLifetimeAppliesAtOnce() =
        runTest {
            lifetime.value = null
            played("Ancient", base - 3_650.days)
            played("Old", base - 40.days)
            played("New", base - 1.days)
            val job = launch { retention().run() }
            runCurrent()
            advanceTimeBy(HistoryRetention.PERIOD * 10)
            runCurrent()

            assertEquals(listOf("Ancient", "Old", "New"), rows())
            assertEquals(3, titles().size)

            lifetime.value = 30.days
            runCurrent()
            assertEquals(listOf("New"), rows())
            assertEquals(listOf("New"), titles())
            job.cancel()
        }

    @Test
    fun aFailedDeletionIsReportedAndTriedAgainLater() =
        runTest {
            played("Old", base - 91.days)
            driver.failNext = "DELETE"
            val job = launch { retention().run() }
            runCurrent()

            assertEquals(listOf("Could not delete old entries from the session history"), warnings)
            assertEquals(listOf("Old"), rows())

            advanceTimeBy(HistoryRetention.PERIOD)
            runCurrent()
            assertEquals(emptyList<String>(), rows())
            assertEquals(1, warnings.size)
            job.cancel()
        }

    @Test
    fun clearingDeletesTheHistoryAndTheTrackChanges() =
        runTest {
            played("Sundown", base)

            assertTrue(retention().clear())

            assertEquals(emptyList<String>(), rows())
            assertEquals(emptyList<String>(), titles())
        }

    @Test
    fun aFailedClearReturnsFalseWithoutTheErrorsText() =
        runTest {
            played("Sundown", base)
            // The last of its three deletions.
            driver.failNext = "DELETE FROM played"

            assertFalse(retention().clear())

            assertEquals(listOf("Could not clear the session history"), warnings)
            // The clear is one transaction: nothing went.
            assertEquals(listOf("Sundown"), rows())
            assertEquals(listOf("Sundown"), titles())
        }

    private companion object {
        const val SPOTIFY = "com.spotify.music"
        val GAP = Settings.DEFAULT_SESSION_GAP
        const val MAX_SESSIONS = 10L
    }
}
