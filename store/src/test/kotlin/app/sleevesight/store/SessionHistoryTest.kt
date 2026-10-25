package app.sleevesight.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.history.HistoryDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
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
    ) = history.started(track, at, GAP) { it.title == track.title }

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
        // Found playing again after a restart: the same entry, begun again then.
        val again = play(sundown, start + 1.minutes)

        assertEquals(first.copy(resumedAt = start + 1.minutes), again)
        assertEquals(listOf(again), history.latestSession())
    }

    @Test
    fun `a track is kept with its length and without its art`() {
        // The privacy policy and the first-run screen say the length stays on the phone, not the cover.
        play(sundown.copy(artUri = "content://art/1"), start)

        assertEquals(listOf(sundown), history.latestSession().map { it.track })
    }

    @Test
    fun `an update of the track added last updates its entry, without its art`() {
        val bare = sundown.copy(artists = "", album = null, duration = 106.seconds)
        val first = play(bare, start)
        val filled = sundown.copy(artUri = "content://art/1")

        val updated = play(filled, start + 2.seconds)

        assertEquals(first.id, updated.id)
        // A start, so begun again then.
        assertEquals(
            listOf(PlayedTrack(first.id, 1, start, sundown, resumedAt = start + 2.seconds)),
            history.latestSession(),
        )
    }

    @Test
    fun `a break longer than the gap after the track should have ended begins a new session`() {
        play(sundown, start)
        val ends = start + 225.seconds

        val carefreeAt = ends + GAP
        val sameSession = play(carefree, carefreeAt)
        // Carefree Highway has no length: the break counts from its start.
        val next = play(sundown, carefreeAt + GAP + 1.milliseconds)

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
                GAP,
            ) { it.title == carefree.title }

        assertEquals(PlayedTrack(updated.id, 1, start, carefree.copy(album = "Live")), history.latestSession().single())
    }

    @Test
    fun `a track that plays on without a row of its own starts`() {
        // The write that added it failed: the last row is the track before.
        play(sundown, start)

        val added = history.continued(carefree, start + 4.minutes, GAP) { it.title == carefree.title }

        assertEquals(listOf(sundown, carefree), history.latestSession().map { it.track })
        assertEquals(start + 4.minutes, added.playedAt)
    }

    @Test
    fun `a resume after a break longer than the gap begins a new session with the resumed track`() {
        play(sundown, start)
        history.stopped(start + 10.seconds)

        // Within the gap: the same entry, playing again.
        val resumed = play(sundown, start + 10.seconds + GAP)
        assertEquals(
            PlayedTrack(resumed.id, 1, start, sundown, resumedAt = start + 10.seconds + GAP),
            history.latestSession().single(),
        )

        history.stopped(start + 1.minutes)
        val later = play(sundown, start + 1.minutes + GAP + 1.milliseconds)
        assertEquals(2L, later.session)
        assertEquals(listOf(sundown), history.latestSession().map { it.track })
    }

    @Test
    fun `a track resumed and left playing when the process ended is heard until a length after the resume`() {
        // Paused 10 s in, resumed 20 minutes later, and the process killed while it played: no stop.
        play(sundown, start)
        history.stopped(start + 10.seconds)
        val resume = start + 10.seconds + 20.minutes
        play(sundown, resume)

        val heard = history.latestSession().single().heardUntil
        assertEquals(resume + 225.seconds, heard)
        // Not a length after it first began, which is before the resume.
        assertTrue(heard > start + 225.seconds)
        // The break counts from there: a gap after it, still the same session.
        assertEquals(1L, play(carefree, resume + 225.seconds + GAP).session)
    }

    @Test
    fun `a track resumed and left playing begins a new session a gap past a length after the resume`() {
        // Paused 4 s before its end, resumed within the gap, then killed while it played.
        play(sundown, start)
        history.stopped(start + 221.seconds)
        val resume = start + 221.seconds + 5.minutes
        play(sundown, resume)

        // Heard until a whole length after the resume: the session check below pins only the upper
        // bound, which a length counted from the first start also meets.
        assertEquals(resume + 225.seconds, history.latestSession().single().heardUntil)
        assertEquals(2L, play(carefree, resume + 225.seconds + GAP + 1.milliseconds).session)
    }

    @Test
    fun `a stream resumed and left playing is heard at least until the resume`() {
        play(carefree, start)
        history.stopped(start + 1.minutes)
        val resume = start + 25.minutes
        play(carefree, resume)

        assertEquals(resume, history.latestSession().single().heardUntil)
        assertEquals(1L, play(sundown, resume + GAP).session)
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
        history.started(carefree, start, gap) { false }

        assertEquals(1L, history.started(sundown, start + gap, gap) { false }.session)
        // Sundown should have ended 225 s after it began.
        val afterSundown = start + gap + 225.seconds
        assertEquals(2L, history.started(carefree, afterSundown + gap + 1.milliseconds, gap) { false }.session)
    }

    @Test
    fun `each start is decided by the gap it is given`() {
        play(sundown, start)
        history.stopped(start + 1.minutes)

        // 20 minutes on: within the default gap, past a 10-minute one.
        assertEquals(2L, history.started(carefree, start + 21.minutes, 10.minutes) { false }.session)
        history.stopped(start + 25.minutes)
        assertEquals(2L, history.started(sundown, start + 45.minutes, GAP) { false }.session)
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

        assertEquals(TrackChangePage(listOf(other, change), next = null), history.changes(limit = 10))
    }

    @Test
    fun `track changes come in pages, newest first, unshifted by changes written meanwhile`() {
        val changes = (0 until 5).map { change(start + it.minutes, "Track $it", states = it) }
        changes.forEach(history::changed)

        val first = history.changes(limit = 2)
        assertEquals(listOf(changes[4], changes[3]), first.changes)
        history.changed(change(start + 10.minutes, "Track 5"))
        val second = history.changes(limit = 2, before = first.next)
        assertEquals(listOf(changes[2], changes[1]), second.changes)
        // Exactly a page left: no page after it.
        assertEquals(TrackChangePage(listOf(changes[0]), next = null), history.changes(limit = 1, before = second.next))
    }

    @Test
    fun `changes after a mark come in pages, and none at or before it`() {
        val changes = (0 until 5).map { change(start + it.minutes, "Track $it") }
        changes.forEach(history::changed)
        // The mark a reset takes after the second change.
        val mark = 2L

        val first = history.changes(limit = 2, after = mark)
        assertEquals(listOf(changes[4], changes[3]), first.changes)
        // The page before the mark holds the one change left after it, and none follows.
        assertEquals(
            TrackChangePage(listOf(changes[2]), next = null),
            history.changes(limit = 2, before = first.next, after = mark),
        )
        assertEquals(TrackChangePage(emptyList(), next = null), history.changes(limit = 2, after = 5))
    }

    @Test
    fun `the last change's id and the sequence are 0 when empty, and only grow`() {
        assertEquals(0L, history.lastChangeId())
        assertEquals(0L, history.changeSequence())

        history.changed(change(start - 91.days, "Old"))
        history.changed(change(start, "New"))
        assertEquals(2L, history.lastChangeId())
        assertEquals(2L, history.changeSequence())

        // A deletion leaves the newest change, and the sequence.
        history.deleteOlderThan(90.days, now = start)
        assertEquals(2L, history.lastChangeId())
        assertEquals(2L, history.changeSequence())

        // A clear empties the table, but the sequence goes on: the next id is 3.
        history.clear()
        assertEquals(0L, history.lastChangeId())
        assertEquals(2L, history.changeSequence())
        history.changed(change(start, "Next"))
        assertEquals(3L, history.lastChangeId())
        assertEquals(3L, history.changeSequence())
    }

    @Test
    fun `old tracks and changes go with their states, and newer ones stay`() {
        val old = start - 91.days
        play(sundown, old)
        history.stopped(old + 4.minutes)
        history.changed(change(old, "Sundown", states = 3))
        play(carefree, start)
        val kept = change(start, "Carefree Highway", states = 2)
        history.changed(kept)

        history.deleteOlderThan(90.days, now = start)

        assertEquals(listOf(carefree), history.latestSession().map { it.track })
        assertTrue(history.session(1).isEmpty())
        assertEquals(listOf(kept), history.changes(limit = 10).changes)
        // The old change's states went with it.
        assertEquals(2L, count("track_change_state"))

        // A cutoff at a track's start keeps it.
        history.deleteOlderThan(Duration.ZERO, now = start)
        assertEquals(listOf(carefree), history.latestSession().map { it.track })
        assertEquals(listOf(kept), history.changes(limit = 10).changes)
    }

    @Test
    fun `a stream playing across the cutoff keeps its row, and is stopped in it`() {
        // A stream with no length, begun 100 days ago and still playing.
        play(carefree, start - 100.days)
        history.changed(change(start, "Carefree Highway"))

        history.deleteOlderThan(90.days, now = start)
        val playing = history.latestSession().single()
        assertEquals(carefree, playing.track)

        history.stopped(start + 1.minutes)
        assertEquals(start + 1.minutes, history.session(playing.session).single().stoppedAt)

        // Stopped, and followed by another track, it goes.
        play(sundown, start + 2.minutes)
        history.deleteOlderThan(90.days, now = start + 2.minutes)
        // Its session, in which Sundown played a minute later.
        assertEquals(listOf(sundown), history.session(playing.session).map { it.track })
    }

    @Test
    fun `the lifetime is counted back from now, whatever the history's newest time`() {
        // Nothing played for 60 days: the rows older than 30 days before now go, all of them.
        for (daysAgo in listOf(100, 70, 60)) {
            play(sundown, start - daysAgo.days)
            history.stopped(start - daysAgo.days + 1.minutes)
        }
        history.changed(change(start - 60.days, "Sundown"))
        play(carefree, start - 60.days + 10.minutes)
        history.stopped(start - 60.days + 11.minutes)

        history.deleteOlderThan(30.days, now = start)

        assertEquals(0L, count("played"))
        assertEquals(0L, count("track_change"))
    }

    @Test
    fun `an empty history has nothing to delete`() {
        history.deleteOlderThan(90.days, now = start)

        assertTrue(history.latestSession().isEmpty())
    }

    @Test
    fun `clearing deletes the history and the track changes together`() {
        play(sundown, start)
        history.changed(
            TrackChangeRecord(SPOTIFY, start, null, TrackRef("Sundown", null), null, listOf(state()), null, null),
        )

        history.clear()

        assertTrue(history.latestSession().isEmpty())
        assertEquals(TrackChangePage(emptyList(), next = null), history.changes(limit = 10))
        // The next track begins the history again, as session 1.
        assertEquals(1L, play(carefree, start + 1.minutes).session)
    }

    private fun count(table: String): Long =
        driver
            .executeQuery(null, "SELECT count(*) FROM $table", { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0)
            }, 0)
            .value

    private fun change(
        at: Instant,
        to: String,
        states: Int = 1,
    ) = TrackChangeRecord(
        SPOTIFY,
        at,
        null,
        TrackRef(to, null),
        null,
        List(states) { state(receivedBeforeMs = 1_000L - it) },
        null,
        null,
    )

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
        val GAP = Settings.DEFAULT_SESSION_GAP
    }
}
