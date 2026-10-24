package app.sleevesight.app.history

import android.media.session.PlaybackState
import app.sleevesight.app.session.SessionRepository
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.state
import app.sleevesight.app.ui.settings.Clearing
import app.sleevesight.core.settings.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The recorder's one queue: *Clear history* after the writes queued before it, whatever the
 * screen that asked does meanwhile. The writes are held at [gate] until a test lets them run.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class) // `getCompleted`, to read a result that must be there.
internal class HistoryRecorderQueueTest : HistoryRecorderFixture() {
    private val gate = Gate()

    override val io: CoroutineContext get() = gate

    @Test
    fun aClearRunsAfterTheWritesQueuedBeforeIt() {
        gate.open = false
        // A start, a change with its stop and start, and a stop, all queued and held.
        val spotify = spotify()
        at(60_000)
        spotify.report(metadata(title = "Carefree Highway"))
        at(120_000)
        spotify.report(state(PlaybackState.STATE_PAUSED, positionMs = 60_000, speed = 0f))
        val cleared = scope.async { recorder.clear() }

        gate.release()

        assertTrue(cleared.isCompleted)
        assertTrue(cleared.getCompleted())
        assertEquals(emptyList<Any>(), allRows())
        assertEquals(emptyList<Any>(), history.changes(limit = 10).changes)
    }

    @Test
    fun theTrackPlayingWhenClearedComesBackAsANewRowOnlyWithItsNextValue() {
        val spotify = spotify()
        at(60_000)
        val cleared = scope.async { recorder.clear() }
        assertTrue(cleared.getCompleted())
        assertEquals(emptyList<Any>(), allRows())

        // Its next value, an update: a row of its own, from then.
        at(90_000)
        spotify.report(metadata(durationMs = 225_000, album = "Live"))

        val row = allRows().single()
        assertEquals(base + 90_000.milliseconds + SessionRepository.SETTLE, row.playedAt)
        assertEquals("Live", row.track.album)
    }

    @Test
    fun aQueuedClearIsMadeWhenItsWaiterIsCancelled() {
        val retention = HistoryRetention(recorder, flowOf(null), scope)
        gate.open = false
        spotify()
        val screen = CoroutineScope(Job() + Dispatchers.Unconfined)
        screen.launch { retention.clear().await() }

        screen.cancel()
        gate.release()

        assertEquals(emptyList<Any>(), allRows())
    }

    @Test
    fun aConfirmedClearIsMadeWhenTheScreenGoesBeforeItsScopeRunsAnything() {
        val retention = HistoryRetention(recorder, flowOf(null), scope)
        gate.open = false
        spotify()
        // The screen's scope runs nothing until the scheduler does, which it never gets to.
        val screen = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
        val clearing = Clearing(screen)

        clearing.start(retention::clear)
        screen.cancel()
        gate.release()

        assertEquals(emptyList<Any>(), allRows())
        assertNull(clearing.ended)
    }

    @Test
    fun theScreenIsToldHowTheClearEnded() {
        val retention = HistoryRetention(recorder, flowOf(null), scope)
        gate.open = false
        spotify()
        val clearing = Clearing(scope)

        clearing.start(retention::clear)
        assertNull(clearing.ended)
        gate.release()

        assertEquals(true, clearing.ended)
    }

    @Test
    fun aDeletionQueuedBeforeTheLifetimeBecameForeverDeletesNothing() {
        history.started(sundown, base - 100.days, Settings.DEFAULT_SESSION_GAP) { false }
        history.stopped(base - 100.days + 4.minutes)
        gate.open = false
        // A write held, and a deletion by 30 days queued behind it.
        spotify(metadata(title = "Carefree Highway"))
        val lifetime = MutableStateFlow<Duration?>(30.days)
        scope.launch { HistoryRetention(recorder, lifetime, scope).run() }

        lifetime.value = null
        gate.release()

        assertEquals(listOf("Sundown", "Carefree Highway"), allRows().map { it.track.title })
    }

    /** Holds what is dispatched to it while closed, and runs it when [release]d. */
    private class Gate : CoroutineDispatcher() {
        var open = true
        private val held = ArrayDeque<Runnable>()

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !open

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            held.addLast(block)
        }

        fun release() {
            open = true
            while (held.isNotEmpty()) held.removeFirst().run()
        }
    }
}

/** Before the settings are first read, nothing is written: the first start waits for the user's gap. */
@RunWith(RobolectricTestRunner::class)
internal class HistoryRecorderFirstReadTest : HistoryRecorderFixture() {
    override val settingsRead = false

    @Test
    fun theFirstStartWaitsForTheSettingsFirstRead() {
        spotify()
        assertEquals(emptyList<Any>(), allRows())

        sessionGap.tryEmit(10.minutes)

        assertEquals(listOf("Sundown"), allRows().map { it.track.title })
    }

    @Test
    fun writesQueuedBeforeTheFirstReadRunInOrderOnceItComes() {
        val spotify = spotify()
        at(60_000)
        spotify.report(metadata(title = "Carefree Highway"))
        at(120_000)
        spotify.report(state(PlaybackState.STATE_PAUSED, positionMs = 60_000, speed = 0f))
        assertEquals(emptyList<Any>(), allRows())
        assertEquals(emptyList<Any>(), history.changes(limit = 10).changes)

        sessionGap.tryEmit(10.minutes)

        val rows = allRows()
        assertEquals(listOf("Sundown", "Carefree Highway"), rows.map { it.track.title })
        assertEquals(base + 60_000.milliseconds + SessionRepository.SETTLE, rows[0].stoppedAt)
        assertEquals(base + 120_000.milliseconds + SessionRepository.SETTLE, rows[1].stoppedAt)
        assertEquals(listOf("Carefree Highway"), history.changes(limit = 10).changes.map { it.to.title })
    }
}
