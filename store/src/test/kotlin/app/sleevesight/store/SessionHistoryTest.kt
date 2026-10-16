package app.sleevesight.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.store.history.HistoryDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SessionHistoryTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HistoryDatabase.Schema.create(it) }
    private val history = SessionHistory(driver)

    private val start = Instant.parse("2026-10-16T08:00:00Z")
    private val sundown =
        NowPlaying(
            title = "Sundown",
            artists = "Gordon Lightfoot",
            album = "Sundown",
            duration = 225.seconds,
            player = SPOTIFY,
            mediaId = "spotify:track:1",
            queueItemId = 3,
        )
    private val carefree = NowPlaying(title = "Carefree Highway", artists = "Gordon Lightfoot", player = SPOTIFY)

    @AfterTest
    fun close() = driver.close()

    private fun play(
        track: NowPlaying,
        at: Instant,
    ) = history.started(track, at) { it.title == track.title }

    @Test
    fun `tracks come back in the order they played, as they were kept`() {
        play(sundown, start)
        play(carefree, start + 4.minutes)

        val session = history.latestSession()
        assertEquals(listOf(sundown, carefree), session.map { it.track })
        assertEquals(listOf(start, start + 4.minutes), session.map { it.playedAt })
        assertEquals(listOf(1L, 1L), session.map { it.session })
    }

    @Test
    fun `the same track again in the same session is not added`() {
        val first = play(sundown, start)
        val again = play(sundown, start + 1.minutes)

        assertEquals(first, again)
        assertEquals(listOf(first), history.latestSession())
    }

    @Test
    fun `an update of the track added last updates its entry, without its art`() {
        val bare = sundown.copy(artists = "", album = null, duration = 106.seconds)
        val first = play(bare, start)
        val filled = sundown.copy(artUri = "content://art/1")

        val updated = play(filled, start + 2.seconds)

        assertEquals(first.id, updated.id)
        assertEquals(listOf(PlayedTrack(first.id, 1, start, sundown)), history.latestSession())
    }

    @Test
    fun `a break longer than the gap after the track should have ended begins a new session`() {
        play(sundown, start)
        val ends = start + 225.seconds

        val carefreeAt = ends + SessionHistory.DEFAULT_SESSION_GAP
        val sameSession = play(carefree, carefreeAt)
        // Carefree Highway has no length: the break counts from its start.
        val next = play(sundown, carefreeAt + SessionHistory.DEFAULT_SESSION_GAP + 1.milliseconds)

        assertEquals(1L, sameSession.session)
        // The same track as the last one, but after the break: a session of its own.
        assertEquals(2L, next.session)
        assertEquals(listOf(sundown), history.latestSession().map { it.track })
        assertEquals(listOf(sundown, carefree), history.session(1).map { it.track })
    }

    @Test
    fun `a track that plays on takes its updates however long it has played`() {
        // A stream with no length, updated with an album 40 minutes in: still the first session.
        play(carefree, start)

        val updated =
            history.continued(
                carefree.copy(album = "Live"),
                start + 40.minutes,
            ) { it.title == carefree.title }

        assertEquals(PlayedTrack(updated.id, 1, start, carefree.copy(album = "Live")), history.latestSession().single())
    }

    @Test
    fun `a track that plays on without a row of its own starts`() {
        // The write that added it failed: the last row is the track before.
        play(sundown, start)

        val added = history.continued(carefree, start + 4.minutes) { it.title == carefree.title }

        assertEquals(listOf(sundown, carefree), history.latestSession().map { it.track })
        assertEquals(start + 4.minutes, added.playedAt)
    }

    @Test
    fun `a resume after a break longer than the gap begins a new session with the resumed track`() {
        play(sundown, start)
        history.stopped(start + 10.seconds)

        // Within the gap: the same entry, playing again.
        val resumed = play(sundown, start + 10.seconds + SessionHistory.DEFAULT_SESSION_GAP)
        assertEquals(PlayedTrack(resumed.id, 1, start, sundown), history.latestSession().single())

        history.stopped(start + 1.minutes)
        val later = play(sundown, start + 1.minutes + SessionHistory.DEFAULT_SESSION_GAP + 1.milliseconds)
        assertEquals(2L, later.session)
        assertEquals(listOf(sundown), history.latestSession().map { it.track })
    }

    @Test
    fun `the break counts from when the track before stopped, even within its length`() {
        play(sundown, start)
        // Paused 10 s in, and something else played 30.5 minutes later: before Sundown's length
        // plus the gap, but after the gap since it stopped. A later stop does not move the first.
        history.stopped(start + 10.seconds)
        history.stopped(start + 2.minutes)
        assertEquals(start + 10.seconds, history.latestSession().single().stoppedAt)

        assertEquals(2L, play(carefree, start + 10.seconds + 30.5.minutes).session)
    }

    @Test
    fun `a track without a length is timed from its start`() {
        val gap = 10.minutes
        val short = SessionHistory(driver, sessionGap = gap)
        short.started(carefree, start) { false }

        assertEquals(1L, short.started(sundown, start + gap) { false }.session)
        // Sundown should have ended 225 s after it began.
        val afterSundown = start + gap + 225.seconds
        assertEquals(2L, short.started(carefree, afterSundown + gap + 1.milliseconds) { false }.session)
    }

    @Test
    fun `a track change comes back with its features and states in order`() {
        val change =
            TrackChangeRecord(
                player = SPOTIFY,
                changedAt = start,
                from = TrackRef("Sundown", "spotify:track:1"),
                to = TrackRef("Carefree Highway", null),
                timeLeft = 11_960.milliseconds,
                states =
                    listOf(
                        state(3_100, "PLAYING", 210_000, 3_100, 1f),
                        state(700, "PLAYING", 212_400, 700, 1f),
                        state(5, "PAUSED", null, null, 0f),
                    ),
                sinceLastAction = null,
                learnedCrossfade = 12.seconds,
            )
        val other =
            change.copy(
                player = "com.google.android.apps.youtube.music",
                from = null,
                states = emptyList(),
                timeLeft = null,
            )

        history.changed(change)
        history.changed(other)

        assertEquals(listOf(change, other), history.changes())
    }

    @Test
    fun `clearing deletes the history and the track changes together`() {
        play(sundown, start)
        history.changed(
            TrackChangeRecord(SPOTIFY, start, null, TrackRef("Sundown", null), null, listOf(state()), null, null),
        )

        history.clear()

        assertTrue(history.latestSession().isEmpty())
        assertTrue(history.changes().isEmpty())
        // The next track begins the history again, as session 1.
        assertEquals(1L, play(carefree, start + 1.minutes).session)
    }

    private fun state(
        receivedBeforeMs: Long = 1_000,
        status: String = "PLAYING",
        positionMs: Long? = 1_000,
        positionBeforeMs: Long? = 1_000,
        speed: Float = 1f,
    ) = PlaybackStateRecord(
        receivedBeforeMs.milliseconds,
        status,
        positionMs?.milliseconds,
        positionBeforeMs?.milliseconds,
        speed,
    )

    private companion object {
        const val SPOTIFY = "com.spotify.music"
    }
}
