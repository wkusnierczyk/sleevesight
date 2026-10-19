package app.sleevesight.app.history

import android.media.session.PlaybackState
import app.sleevesight.app.session.CorruptMarkException
import app.sleevesight.app.session.FakeSession
import app.sleevesight.app.session.LearningMark
import app.sleevesight.app.session.SessionRepository
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.state
import app.sleevesight.app.session.SkipDetector
import app.sleevesight.app.session.SkipDetectorLearning
import app.sleevesight.core.settings.Crossfade
import app.sleevesight.store.PlaybackStateRecord
import app.sleevesight.store.TrackRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What a track change is recorded with, and a failed write (`design/architecture.md`, *Learning
 * skip detection*, D15). The history's rows for each sequence are in [HistoryRecorderSequencesTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
internal class HistoryRecorderTest : HistoryRecorderFixture() {
    @Test
    fun aTrackChangeIsRecordedWithItsFeatures() {
        val spotify = spotify()
        at(6_000)
        spotify.send(state(positionMs = 200_000, updatedAt = 6_000))
        at(6_500)
        spotify.send(state(positionMs = 200_500, updatedAt = 6_500))
        at(8_000)
        spotify.send(metadata(title = "Carefree Highway"))
        settleAll()

        val change = history.changes(limit = 10).changes.single()
        assertEquals(SPOTIFY, change.player)
        assertEquals(base + 8.seconds, change.changedAt)
        assertEquals(TrackRef("Sundown", null), change.from)
        assertEquals(TrackRef("Carefree Highway", null), change.to)
        assertEquals(23.seconds, change.timeLeft)
        assertEquals(
            listOf(
                PlaybackStateRecord(2.seconds, "PLAYING", 200.seconds, 2.seconds, 1f),
                PlaybackStateRecord(1_500.milliseconds, "PLAYING", 200_500.milliseconds, 1_500.milliseconds, 1f),
            ),
            change.states,
        )
        // No action yet, and no crossfade learned.
        assertNull(change.sinceLastAction)
        assertNull(change.learnedCrossfade)
    }

    @Test
    fun aTrackChangeIsRecordedWithTheCrossfadeLearnedAndTheTimeSinceTheLastAction() {
        val spotify = spotify()
        // Two natural ends at a 12 s crossfade, each with its late cluster, then one without it.
        spotify.endsAt(10_000, LENGTH_MS, cluster = true, next = "Two")
        spotify.endsAt(20_000, LENGTH_MS, cluster = true, next = "Three")
        at(30_000)
        actions.acted()
        at(39_000)
        spotify.endsAt(40_000, LENGTH_MS, cluster = false, next = "Four")
        // An action after the change began is not the last before it.
        actions.acted()
        settleAll()

        val changes = history.changes(limit = 10).changes.reversed()
        assertEquals(listOf(null, null, 12.seconds), changes.map { it.learnedCrossfade })
        assertEquals(listOf(null, null, 10.seconds), changes.map { it.sinceLastAction })
        assertEquals(mapOf(SPOTIFY to 12.seconds), skips.learned.value)

        // A new process relearns it from the changes recorded.
        val restarted = restarted()
        assertTrue(restarted.restored.value)
        assertEquals(mapOf(SPOTIFY to 12.seconds), restarted.learned.value)

        // *Clear history* forgets it.
        assertTrue(scope.async { recorder.clear() }.getCompleted())
        assertEquals(emptyMap<String, Any>(), skips.learned.value)
    }

    @Test
    fun aResetLastsAcrossARestartAndLaterChangesAreLearned() {
        val spotify = spotify()
        spotify.endsAt(10_000, LENGTH_MS, cluster = true, next = "Two")
        spotify.endsAt(20_000, LENGTH_MS, cluster = true, next = "Three")
        assertEquals(mapOf(SPOTIFY to 12.seconds), skips.learned.value)

        assertTrue(runBlocking { learning.reset() })
        assertEquals(emptyMap<String, Any>(), skips.learned.value)
        assertEquals(history.lastChangeId(), mark.id)
        // The history stays; a new process learns nothing from what came before the reset.
        assertEquals(2, history.changes(limit = 10).changes.size)
        assertEquals(emptyMap<String, Any>(), restarted().learned.value)

        // At a 5 s crossfade now: learned, and relearned by a new process, alone.
        spotify.endsAt(30_000, LENGTH_MS, cluster = true, next = "Four", leftMs = 5_000)
        spotify.endsAt(40_000, LENGTH_MS, cluster = true, next = "Five", leftMs = 5_000)
        assertEquals(mapOf(SPOTIFY to 5.seconds), skips.learned.value)
        assertEquals(mapOf(SPOTIFY to 5.seconds), restarted().learned.value)
    }

    @Test
    fun aResetWhoseMarkCannotBeWrittenFailsAndKeepsTheLearning() {
        val spotify = spotify()
        spotify.endsAt(10_000, LENGTH_MS, cluster = true, next = "Two")
        spotify.endsAt(20_000, LENGTH_MS, cluster = true, next = "Three")

        mark.failWrite = true
        assertFalse(runBlocking { learning.reset() })
        assertEquals(mapOf(SPOTIFY to 12.seconds), skips.learned.value)
        assertEquals(0L, mark.id)
    }

    @Test
    fun aCorruptMarkRelearnsNothingAndIsTakenAsAResetThen() {
        val spotify = spotify()
        spotify.endsAt(10_000, LENGTH_MS, cluster = true, next = "Two")
        spotify.endsAt(20_000, LENGTH_MS, cluster = true, next = "Three")

        mark.corrupt = true
        val restarted = restarted()
        assertTrue(restarted.restored.value)
        assertEquals(emptyMap<String, Any>(), restarted.learned.value)
        // Written anew as of then: the next start relearns only what is recorded after it.
        assertEquals(history.lastChangeId(), mark.id)
        mark.corrupt = false
        assertEquals(emptyMap<String, Any>(), restarted().learned.value)
        spotify.endsAt(30_000, LENGTH_MS, cluster = true, next = "Four", leftMs = 5_000)
        spotify.endsAt(40_000, LENGTH_MS, cluster = true, next = "Five", leftMs = 5_000)
        assertEquals(mapOf(SPOTIFY to 5.seconds), restarted().learned.value)
    }

    @Test
    fun aMarkThatCannotBeReadForAMomentRelearnsNothingThenAndIsKept() {
        val spotify = spotify()
        spotify.endsAt(10_000, LENGTH_MS, cluster = true, next = "Two")
        spotify.endsAt(20_000, LENGTH_MS, cluster = true, next = "Three")
        assertTrue(runBlocking { learning.reset() })
        val resetAt = mark.id
        spotify.endsAt(30_000, LENGTH_MS, cluster = true, next = "Four", leftMs = 5_000)
        spotify.endsAt(40_000, LENGTH_MS, cluster = true, next = "Five", leftMs = 5_000)

        // An I/O error, not a corrupt file: nothing relearned at that start, and the mark kept.
        mark.failRead = true
        assertEquals(emptyMap<String, Any>(), restarted().learned.value)
        assertEquals(resetAt, mark.id)
        // The next start relearns the changes since the real reset.
        mark.failRead = false
        assertEquals(mapOf(SPOTIFY to 5.seconds), restarted().learned.value)
    }

    @Test
    fun aMarkFromAnEarlierHistoryHidesNoChangeOfThisOne() {
        // The history was created anew since the reset (a corrupt file is deleted): ids from 1 again.
        val spotify = spotify()
        spotify.endsAt(10_000, LENGTH_MS, cluster = true, next = "Two")
        spotify.endsAt(20_000, LENGTH_MS, cluster = true, next = "Three")
        mark.id = 5_000

        assertEquals(mapOf(SPOTIFY to 12.seconds), restarted().learned.value)
        assertEquals(0L, mark.id)
    }

    @Test
    fun aResetDuringARestoreIsNotOverwrittenByIt() {
        // The restore reads a mark from an earlier history; the user resets before it rewrites it.
        val spotify = spotify()
        spotify.endsAt(10_000, LENGTH_MS, cluster = true, next = "Two")
        spotify.endsAt(20_000, LENGTH_MS, cluster = true, next = "Three")
        mark.id = 5_000
        val gate = CompletableDeferred<Unit>()
        mark.readGate = gate

        runBlocking {
            // One learning, as the app has, restoring and reset.
            val restoring = launch(start = CoroutineStart.UNDISPATCHED) { learning.restore() }
            val resetting = async(start = CoroutineStart.UNDISPATCHED) { learning.reset() }
            gate.complete(Unit)
            restoring.join()
            assertTrue(resetting.await())
        }

        assertEquals(history.lastChangeId(), mark.id)
    }

    @Test
    fun aMarkAtTheSequenceAfterAClearIsKept() {
        // Cleared after the reset: the table is empty, but its ids go on from the mark.
        val spotify = spotify()
        spotify.endsAt(10_000, LENGTH_MS, cluster = true, next = "Two")
        spotify.endsAt(20_000, LENGTH_MS, cluster = true, next = "Three")
        assertTrue(runBlocking { learning.reset() })
        assertTrue(scope.async { recorder.clear() }.getCompleted())

        assertEquals(emptyMap<String, Any>(), restarted().learned.value)
        assertEquals(2L, mark.id)
    }

    private val mark = MemoryMark()
    private val learning by lazy { SkipDetectorLearning(skips, history, mark, EmptyCoroutineContext) }

    /** A new process's skip detection, restored from this history and [mark]. */
    private fun restarted(): SkipDetector {
        val detector = SkipDetector { Crossfade.Autodetect }
        runBlocking { SkipDetectorLearning(detector, history, mark, EmptyCoroutineContext).restore() }
        return detector
    }

    /** The mark in memory, failing on request. */
    private class MemoryMark : LearningMark {
        var id = 0L
        var failRead = false
        var corrupt = false
        var failWrite = false

        /** While set, a read takes the mark, then waits for it before it returns. */
        var readGate: CompletableDeferred<Unit>? = null

        override suspend fun read(): Long {
            if (failRead) throw IOException("unreadable")
            if (corrupt) throw CorruptMarkException("corrupt")
            val read = id
            readGate?.await()
            return read
        }

        override suspend fun write(id: Long) {
            if (failWrite) throw IOException("disk full")
            this.id = id
        }
    }

    /**
     * The track playing, of [lengthMs], ends at [changeMs] with [leftMs] left, at that crossfade:
     * Spotify's late cluster of six `PLAYING` states before it if [cluster], else one state. Then
     * [next] plays, also of [lengthMs].
     */
    private fun FakeSession.endsAt(
        changeMs: Long,
        lengthMs: Long,
        cluster: Boolean,
        next: String,
        leftMs: Long = 12_000,
    ) {
        val times = if (cluster) (changeMs - 600..changeMs - 100 step 100) else listOf(changeMs - 100)
        for (ms in times) {
            at(ms)
            send(state(positionMs = lengthMs - leftMs - (changeMs - ms), updatedAt = ms))
        }
        at(changeMs)
        report(metadata(title = next, durationMs = lengthMs))
    }

    @Test
    fun aStateWithNoPositionHasNoTimeForIt() {
        val spotify = spotify()
        at(6_000)
        spotify.send(state(PlaybackState.STATE_BUFFERING, positionMs = PlaybackState.PLAYBACK_POSITION_UNKNOWN))
        at(7_000)
        spotify.report(metadata(title = "Carefree Highway"))

        val state =
            history
                .changes(limit = 10)
                .changes
                .single()
                .states
                .single()
        assertEquals("BUFFERING", state.status)
        assertNull(state.position)
        assertNull(state.positionBefore)
    }

    @Test
    fun aFailedWriteIsReportedAndTheNextOneIsMade() {
        driver.failNext = "INSERT INTO played"
        val spotify = spotify()
        assertEquals(listOf("Could not record a track played in the session history"), warnings)

        at(60_000)
        spotify.report(metadata(title = "Carefree Highway"))

        assertEquals(listOf("Carefree Highway"), history.latestSession().map { it.track.title })
        assertEquals(1, history.changes(limit = 10).changes.size)
    }

    @Test
    fun aFailedResumeIsStartedAgainAtItsTimeWithTheNextUpdate() {
        // Paused 10 s in, resumed 40 minutes later with that write failing, then given an album.
        val spotify = spotify()
        at(10_000)
        spotify.report(state(PlaybackState.STATE_PAUSED, positionMs = 10_000, speed = 0f))
        val resume = 10_000 + 40 * 60_000L
        at(resume)
        driver.failNext = "INSERT INTO played"
        spotify.report(state(positionMs = 10_000, updatedAt = resume))
        assertEquals(listOf("Could not record a track played in the session history"), warnings)
        at(resume + 60_000)
        spotify.report(metadata(durationMs = 225_000, album = "Live"))

        val settle = SessionRepository.SETTLE
        val first = history.session(1).single()
        assertEquals(base + 10.seconds + settle, first.stoppedAt)
        val second = history.session(2).single()
        assertEquals(base + resume.milliseconds + settle, second.playedAt)
        assertEquals("Live", second.track.album)
    }

    @Test
    fun aNewSessionGapAppliesFromTheNextStart() {
        // Paused 10 s in, resumed 15 minutes later: within the default gap.
        val spotify = spotify()
        at(10_000)
        spotify.report(state(PlaybackState.STATE_PAUSED, positionMs = 10_000, speed = 0f))
        val resume = 10_000 + 15 * 60_000L
        at(resume)
        spotify.report(state(positionMs = 10_000, updatedAt = resume))
        assertEquals(1, allRows().size)

        // Then the gap is set to 10 minutes, and the same pause and resume begins a new session.
        sessionGap.tryEmit(10.minutes)
        at(resume + 10_000)
        spotify.report(state(PlaybackState.STATE_PAUSED, positionMs = 20_000, speed = 0f))
        val later = resume + 10_000 + 15 * 60_000L
        at(later)
        spotify.report(state(positionMs = 20_000, updatedAt = later))

        assertEquals(listOf(1L, 2L), allRows().map { it.session })
    }

    private companion object {
        const val LENGTH_MS = 225_000L
    }
}
