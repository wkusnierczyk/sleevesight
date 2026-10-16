package app.sleevesight.app.history

import android.database.SQLException
import android.os.SystemClock
import android.util.Log
import app.sleevesight.app.session.PlaybackStatus
import app.sleevesight.app.session.SessionChange
import app.sleevesight.app.session.SessionRepository
import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.store.PlaybackStateRecord
import app.sleevesight.store.SessionHistory
import app.sleevesight.store.TrackChangeRecord
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Writes what the session layer sees to the session history and the record of track changes
 * ([SessionHistory]; `design/architecture.md`, *Session layer* and *Learning skip detection*, D15),
 * whether or not the app is visible: it runs as long as the process, started by the app graph, and
 * the session layer runs while the listener service is bound, screen or no screen. Nothing reaches
 * it before the first run is acknowledged, since the session layer publishes nothing until then.
 *
 * - **The history**: each track shown once it is seen playing, so a track only paused on screen (a
 *   player's last track, found at start) is not one played. While it is the track added last, in
 *   the same listening session, it is not added again: a pause and resume is no new entry, an
 *   update of it (artists sent after the title, a new length) updates its entry, and so does
 *   the same track shown again after a restart.
 * - **Each track change** ([SessionRepository.lastChange]) with its features: the time the
 *   previous track had left ([SessionChange.timeLeft]), the playback states before it, and the
 *   player. Two features have no source yet and are recorded as null: the time since the user's
 *   last action in Sleevesight (nothing records the actions yet: the first action, #53, and the
 *   transport controls, Phase 2), and the crossfade learned from natural ends (#53).
 *
 * The session layer's times are on the [elapsed] clock (`SystemClock.elapsedRealtime`), which
 * restarts with the phone, so they are kept as wall-clock times from [wall] and as offsets from the
 * change. Writes go through one queue, in the order they were seen, each on [io]; a write that fails
 * (the disk full, say) is reported to [warn] and dropped, never thrown, so it does not end the run.
 */
class HistoryRecorder(
    private val sessions: SessionRepository,
    private val history: SessionHistory,
    private val io: CoroutineContext,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val wall: Clock = Clock.System,
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
) {
    /** Records until the calling coroutine is cancelled. */
    suspend fun run(): Unit =
        coroutineScope {
            // The collectors only queue, so they never suspend: a state flow's value is not missed
            // while a write is in progress.
            val writes = Channel<Write>(Channel.UNLIMITED)
            launch {
                sessions.lastChange.filterNotNull().collect { change ->
                    val record = record(change)
                    writes.trySend(Write("a track change") { history.changed(record) })
                }
            }
            launch {
                playing().collect { track ->
                    val at = wall.now()
                    writes.trySend(Write("a track played") { history.played(track, at) { sameAsLast(it, track) } })
                }
            }
            for (write in writes) {
                try {
                    withContext(io) { write.run() }
                } catch (_: SQLException) {
                    // No exception text: a database message may name a path or a track.
                    warn("Could not record ${write.what} in the session history")
                }
            }
        }

    /** The track shown while it plays, again whenever the player updates it. */
    private fun playing(): Flow<NowPlaying> =
        combine(sessions.nowPlaying, sessions.playback) { track, playback ->
            track?.takeIf { playback.status == PlaybackStatus.PLAYING && playback.player == it.player }
        }.filterNotNull().distinctUntilChanged()

    private fun record(change: SessionChange): TrackChangeRecord =
        TrackChangeRecord(
            player = change.track.player,
            changedAt = wallTime(change.changedAt),
            timeLeft = change.timeLeft,
            states =
                change.states.map { report ->
                    PlaybackStateRecord(
                        receivedBefore = (change.changedAt - report.at).milliseconds,
                        status = report.state.status.name,
                        position = report.state.position,
                        positionBefore = (change.changedAt - report.state.updatedAt).milliseconds,
                        speed = report.state.speed,
                    )
                },
            sinceLastAction = null,
            learnedCrossfade = null,
        )

    /** [at], on the [elapsed] clock, as a wall-clock time. */
    private fun wallTime(at: Long): Instant = wall.now() - (elapsed() - at).milliseconds

    private class Write(
        val what: String,
        val run: () -> Unit,
    )

    private companion object {
        const val TAG = "Sleevesight"

        /**
         * Whether [last], the track added last, is [track] again: the same track as the session
         * layer decides ([TrackIdentity]), with the same title. Stricter by the title, since after a
         * restart a queue item id may name another track, and a duplicate entry costs less than a
         * lost one; a title changing under one queue item (a stream) is a new track anyway.
         */
        fun sameAsLast(
            last: NowPlaying,
            track: NowPlaying,
        ): Boolean = TrackIdentity.same(last, track) && last.title == track.title
    }
}
