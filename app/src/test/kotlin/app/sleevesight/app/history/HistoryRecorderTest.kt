package app.sleevesight.app.history

import android.media.session.PlaybackState
import app.sleevesight.app.session.SessionRepository
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.state
import app.sleevesight.store.PlaybackStateRecord
import app.sleevesight.store.TrackRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
        // Nothing supplies these yet: the user's actions (#53, Phase 2) and the learned crossfade (#53).
        assertNull(change.sinceLastAction)
        assertNull(change.learnedCrossfade)
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
}
