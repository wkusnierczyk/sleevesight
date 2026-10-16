package app.sleevesight.app.history

import android.database.SQLException
import android.media.session.PlaybackState
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.sleevesight.app.session.FakeSession
import app.sleevesight.app.session.SessionRepositoryFixture
import app.sleevesight.app.session.Sessions.SPOTIFY
import app.sleevesight.app.session.Sessions.YOUTUBE_MUSIC
import app.sleevesight.app.session.Sessions.metadata
import app.sleevesight.app.session.Sessions.state
import app.sleevesight.store.PlaybackStateRecord
import app.sleevesight.store.SessionHistory
import app.sleevesight.store.TrackRef
import app.sleevesight.store.history.HistoryDatabase
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The session layer's tracks and track changes, written to the history (`design/architecture.md`,
 * *Learning skip detection*, D15): with no screen at all, as the listener service runs it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
internal class HistoryRecorderTest : SessionRepositoryFixture() {
    private val jdbc = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HistoryDatabase.Schema.create(it) }
    private val driver = FailingDriver(jdbc)
    private val history = SessionHistory(driver)
    private val warnings = mutableListOf<String>()
    private val base = Instant.parse("2026-10-16T08:00:00Z")
    private val wall =
        object : Clock {
            override fun now(): Instant = base + scheduler.currentTime.milliseconds
        }

    @Before
    fun record() {
        val recorder =
            HistoryRecorder(
                repository,
                history,
                io = EmptyCoroutineContext,
                elapsed = { scheduler.currentTime },
                wall = wall,
                warn = { warnings += it },
            )
        scope.launch { recorder.run() }
    }

    @After
    fun close() = jdbc.close()

    private fun spotify(playbackState: PlaybackState = state()): FakeSession {
        val spotify = FakeSession(SPOTIFY, metadata = metadata(durationMs = 225_000), playbackState = playbackState)
        system.active = listOf(spotify)
        repository.start()
        return spotify
    }

    private fun at(ms: Long) = scheduler.advanceTimeBy((ms - scheduler.currentTime).milliseconds)

    @Test
    fun eachTrackPlayedIsAddedOnceInOrder() {
        val spotify = spotify()
        at(60_000)
        spotify.settled(metadata(title = "Carefree Highway"))

        val played = history.latestSession()
        assertEquals(listOf("Sundown", "Carefree Highway"), played.map { it.track.title })
        // The first track from when it was found playing; the next from when its change began.
        assertEquals(listOf(base, base + 60.seconds), played.map { it.playedAt })
    }

    @Test
    fun aPausedTrackIsNotPlayedWhenTheNextTracksStateComesBeforeItsMetadata() {
        // YouTube Music, paused, then the next track's state just before its metadata.
        followed.value = setOf(YOUTUBE_MUSIC)
        val youtube =
            FakeSession(YOUTUBE_MUSIC, playbackState = state(PlaybackState.STATE_PAUSED, speed = 0f, queueItem = 4))
        system.active = listOf(youtube)
        repository.start()

        at(10_000)
        youtube.send(state(queueItem = 5))
        at(10_100)
        youtube.settled(metadata(title = "Carefree Highway"))

        assertEquals(listOf("Carefree Highway"), history.latestSession().map { it.track.title })
    }

    @Test
    fun aShortPauseKeepsOneEntry() {
        val spotify = spotify()
        at(10_000)
        spotify.settled(state(PlaybackState.STATE_PAUSED, positionMs = 10_000, speed = 0f, updatedAt = 10_000))
        at(70_000)
        spotify.settled(state(positionMs = 10_000, updatedAt = 70_000))

        assertEquals(listOf(1L), history.latestSession().map { it.session })
    }

    @Test
    fun aResumeAfterALongBreakBeginsANewSessionWithTheResumedTrack() {
        val spotify = spotify()
        at(10_000)
        spotify.settled(state(PlaybackState.STATE_PAUSED, positionMs = 10_000, speed = 0f, updatedAt = 10_000))
        val resumeAt = 10_000 + (SessionHistory.DEFAULT_SESSION_GAP + 1.seconds).inWholeMilliseconds
        at(resumeAt)
        spotify.settled(state(positionMs = 10_000, updatedAt = resumeAt))

        val session = history.latestSession()
        assertEquals(listOf("Sundown"), session.map { it.track.title })
        assertEquals(2L, session.single().session)
    }

    @Test
    fun theSessionLayerStoppedAndStartedAgainKeepsOneEntry() {
        spotify()
        at(30_000)
        repository.stop()
        at(31_000)
        repository.start()
        settle()

        assertEquals(1, history.latestSession().size)
        assertEquals(1, history.session(1).size)
    }

    @Test
    fun artistsSentAfterTheTitleUpdateTheEntry() {
        val spotify = FakeSession(SPOTIFY, metadata = metadata(artist = null))
        system.active = listOf(spotify)
        repository.start()
        at(1_000)
        spotify.settled(metadata())

        assertEquals(listOf("Gordon Lightfoot"), history.latestSession().map { it.track.artists })
    }

    @Test
    fun aNewTitleUnderTheSameMediaIdIsANewEntry() {
        val spotify = FakeSession(SPOTIFY, metadata = metadata(mediaId = "spotify:track:1"))
        system.active = listOf(spotify)
        repository.start()
        at(1_000)
        spotify.settled(metadata(title = "Sundown (Live)", mediaId = "spotify:track:1"))

        assertEquals(listOf("Sundown", "Sundown (Live)"), history.latestSession().map { it.track.title })
    }

    @Test
    fun aTrackOnlyPausedIsNotPlayedUntilItPlays() {
        val spotify = spotify(state(PlaybackState.STATE_PAUSED, speed = 0f))
        assertTrue(history.latestSession().isEmpty())

        spotify.settled(state())

        assertEquals(listOf("Sundown"), history.latestSession().map { it.track.title })
    }

    @Test
    fun aTrackChangeIsRecordedWithItsFeatures() {
        val spotify = spotify()
        at(6_000)
        spotify.send(state(positionMs = 200_000, updatedAt = 6_000))
        at(6_500)
        spotify.send(state(positionMs = 200_500, updatedAt = 6_500))
        at(8_000)
        spotify.send(metadata(title = "Carefree Highway"))
        settle()

        val change = history.changes().single()
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
        spotify.settled(metadata(title = "Carefree Highway"))

        val state =
            history
                .changes()
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
        spotify.settled(metadata(title = "Carefree Highway"))

        assertEquals(listOf("Carefree Highway"), history.latestSession().map { it.track.title })
        assertEquals(1, history.changes().size)
    }

    /** A driver whose next statement starting with [failNext] fails, as a full disk would. */
    private class FailingDriver(
        private val driver: SqlDriver,
    ) : SqlDriver by driver {
        var failNext: String? = null

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            val prefix = failNext
            if (prefix != null && sql.trimStart().startsWith(prefix)) {
                failNext = null
                throw SQLException("disk full")
            }
            return driver.execute(identifier, sql, parameters, binders)
        }
    }
}
