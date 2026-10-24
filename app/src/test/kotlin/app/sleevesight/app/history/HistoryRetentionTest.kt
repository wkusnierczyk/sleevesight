package app.sleevesight.app.history

import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.TrackChangeRecord
import app.sleevesight.store.TrackRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * *Keep history for* and *Clear history*, through the recorder's queue, in virtual time; the wall
 * clock reads [base] plus it.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class) // `getCompleted`, to read a result that must be there.
internal class HistoryRetentionTest : HistoryRecorderFixture() {
    private val lifetime = MutableStateFlow<Duration?>(Settings.DEFAULT_HISTORY_LIFETIME)
    private val retention by lazy { HistoryRetention(recorder, lifetime, scope) }

    /** A track played and stopped a minute later, and a change into it, at [at]: a session of its own. */
    private fun played(
        title: String,
        at: Instant,
    ) {
        history.started(NowPlaying(title = title, artists = "Gordon Lightfoot", player = SPOTIFY), at, GAP) { false }
        history.stopped(at + 1.minutes)
        history.changed(TrackChangeRecord(SPOTIFY, at, null, TrackRef(title, null), null, emptyList(), null, null))
    }

    /** Moves virtual time on to the end of period [n], and runs what is due then. */
    private fun atPeriod(n: Int = 1) {
        at((HistoryRetention.PERIOD * n).inWholeMilliseconds)
        scheduler.runCurrent()
    }

    private val clockMoved = "The clock moved: old entries in the session history are kept until it is set right"

    private fun changes(): List<String> = history.changes(limit = 100).changes.map { it.to.title }

    /** The titles in the history, oldest first. */
    private fun rows(): List<String> = (1L..MAX_SESSIONS).flatMap(history::session).map { it.track.title }

    @Test
    fun whatIsOlderThanTheLifetimeGoesAtStartAndAfterEachPeriod() {
        played("Old", base - 91.days)
        // Past 90 days three hours from now, so at the next purge but not this one.
        played("Turning", base - 90.days + 3.hours)
        played("New", base)
        scope.launch { retention.run() }

        assertEquals(listOf("Turning", "New"), rows())
        assertEquals(listOf("New", "Turning"), changes())

        at((HistoryRetention.PERIOD - 1.milliseconds).inWholeMilliseconds)
        assertEquals(listOf("Turning", "New"), rows())
        atPeriod()
        assertEquals(listOf("New"), rows())
        assertEquals(listOf("New"), changes())
    }

    @Test
    fun theLifetimeIsCountedBackFromTodayEvenWhenNothingHasPlayedSince() {
        lifetime.value = 30.days
        played("Old", base - 40.days)
        played("Older", base - 45.days)
        scope.launch { retention.run() }

        assertEquals(emptyList<String>(), rows())
        assertEquals(emptyList<String>(), changes())
    }

    @Test
    fun aClockSetAheadWhileTheAppRunsDeletesNothingUntilItIsSetRight() {
        played("Old", base - 80.days)
        played("New", base - 1.days)
        scope.launch { retention.run() }
        assertEquals(listOf("Old", "New"), rows())

        // Set 200 days ahead, and a track recorded by that clock.
        clockSet = 200.days
        spotify()
        atPeriod()
        lifetime.value = 30.days

        assertEquals(listOf("Old", "New", "Sundown"), rows())
        assertEquals(listOf(clockMoved, clockMoved), warnings)

        // Set right: the next deletion runs, by the right clock.
        clockSet = Duration.ZERO
        atPeriod(2)
        assertEquals(listOf("New", "Sundown"), rows())
        assertEquals(2, warnings.size)
    }

    @Test
    fun aClockSetBackWhileTheAppRunsDeletesNothingUntilItIsSetRight() {
        played("Old", base - 91.days)
        played("New", base)
        clockSet = -2.hours
        scope.launch { retention.run() }

        assertEquals(listOf("Old", "New"), rows())
        assertEquals(listOf(clockMoved), warnings)

        clockSet = Duration.ZERO
        atPeriod()
        assertEquals(listOf("New"), rows())
    }

    @Test
    fun aClockMovedWithinTheMarginStillDeletes() {
        // Half an hour: more than a network time correction, within the hour allowed.
        played("Old", base - 91.days)
        played("New", base)
        clockSet = 30.minutes
        scope.launch { retention.run() }

        assertEquals(listOf("New"), rows())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun foreverDoesNotCheckTheClock() {
        // Through the recorder itself: the retention asks it nothing with *Forever*.
        played("Ancient", base - 3_650.days)
        clockSet = 200.days

        assertTrue(scope.async { recorder.deleteOlderThan { null } }.getCompleted())
        assertTrue(warnings.isEmpty())
        assertEquals(listOf("Ancient"), rows())
    }

    @Test
    fun whileTheClockIsMovedAClearAndTheRecordingStillWork() {
        played("Old", base - 91.days)
        clockSet = 200.days
        scope.launch { retention.run() }
        assertEquals(listOf(clockMoved), warnings)

        assertTrue(retention.clear().getCompleted())
        assertEquals(emptyList<String>(), rows())
        assertEquals(emptyList<String>(), changes())

        spotify()
        assertEquals(listOf("Sundown"), rows())
    }

    @Test
    fun aClockSetAheadBeforeTheAppStartsIsNotCaught() {
        // The documented limit: a process started after the jump takes the jumped clock as right.
        clockSet = 200.days
        played("Recent", base - 1.days)
        val restarted = newRecorder()
        scope.launch { restarted.run() }
        scope.launch { HistoryRetention(restarted, lifetime, scope).run() }

        assertEquals(emptyList<String>(), rows())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun foreverDeletesNothingAndAShorterLifetimeAppliesAtOnce() {
        lifetime.value = null
        played("Ancient", base - 3_650.days)
        played("Old", base - 40.days)
        played("New", base)
        scope.launch { retention.run() }
        at((HistoryRetention.PERIOD * 10).inWholeMilliseconds)

        assertEquals(listOf("Ancient", "Old", "New"), rows())
        assertEquals(3, changes().size)

        lifetime.value = 30.days
        assertEquals(listOf("New"), rows())
        assertEquals(listOf("New"), changes())
    }

    @Test
    fun aFailedDeletionIsReportedAndTriedAgainLater() {
        played("Old", base - 91.days)
        played("New", base)
        driver.failNext = "DELETE"
        scope.launch { retention.run() }

        assertEquals(listOf("Could not delete old entries from the session history"), warnings)
        assertEquals(listOf("Old", "New"), rows())

        atPeriod()
        assertEquals(listOf("New"), rows())
        assertEquals(1, warnings.size)
    }

    @Test
    fun clearingDeletesTheHistoryAndTheTrackChanges() {
        played("Sundown", base)

        val cleared = retention.clear()

        assertTrue(cleared.getCompleted())
        assertEquals(emptyList<String>(), rows())
        assertEquals(emptyList<String>(), changes())
    }

    @Test
    fun aFailedClearReturnsFalseWithoutTheErrorsText() {
        played("Sundown", base)
        // The last of its three deletions.
        driver.failNext = "DELETE FROM played"

        assertFalse(retention.clear().getCompleted())

        assertEquals(listOf("Could not clear the session history"), warnings)
        // The clear is one transaction: nothing went.
        assertEquals(listOf("Sundown"), rows())
        assertEquals(listOf("Sundown"), changes())
    }

    private companion object {
        const val SPOTIFY = "com.spotify.music"
        val GAP = Settings.DEFAULT_SESSION_GAP
        const val MAX_SESSIONS = 10L
    }
}
