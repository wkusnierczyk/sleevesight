package app.sleevesight.app.history

import android.database.SQLException
import android.os.SystemClock
import android.util.Log
import app.sleevesight.app.session.PlaybackInfo
import app.sleevesight.app.session.SessionChange
import app.sleevesight.app.session.SessionRepository
import app.sleevesight.app.session.TrackIdentity
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.settings.Settings
import app.sleevesight.store.PlaybackStateRecord
import app.sleevesight.store.SessionHistory
import app.sleevesight.store.TrackChangeRecord
import app.sleevesight.store.TrackRef
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Writes what the session layer sees to the session history and the record of track changes
 * ([SessionHistory]; `design/architecture.md`, *Session layer* and *Learning skip detection*, D15),
 * whether or not the app is visible: it runs as long as the process, started by the app graph, and
 * the session layer runs while the listener service is bound, screen or no screen. Nothing reaches
 * it before the first run is acknowledged, since the session layer publishes nothing until then.
 *
 * - **The history**, from [SessionRepository.playing] (a settled track and state), so a track
 *   only paused on screen (a player's last track, found at start) is not one played, nor is it
 *   when the next track's state arrives before its metadata. Each value is one of three events,
 *   which [SessionHistory] turns into rows by its rules:
 *   - the same track as the one playing before ([sameAsLast]), updated: it *continues*, and its
 *     row takes the update however long it has played;
 *   - another track, or a track after none (a resume, a restart, the session layer started): the
 *     one before, if any, *stopped*, and this one *started*, which alone decides whether a new
 *     listening session begins, by the [sessionGap] when it is seen (the user's *Start a new
 *     session after a break of*; the default until the settings are first read);
 *   - none: the one before *stopped* (paused, stopped, the session layer stopped).
 * - **Times in the history** are when the recorder sees them: a track's start when it is first seen
 *   playing, its stop when it is first seen to stop, both as [SessionRepository.playing] publishes
 *   them, after the player's reports have settled. So they lag the reports by up to the settle
 *   window, and a track that buffers before it plays starts once it plays. The precise time of a
 *   change is its record's `changedAt`; its `from` and `to` name the tracks it links.
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
@Suppress("LongParameterList") // What it records from and to, and the user's gap; the rest are test seams.
class HistoryRecorder(
    private val sessions: SessionRepository,
    private val history: SessionHistory,
    private val io: CoroutineContext,
    private val sessionGap: Flow<Duration> = flowOf(Settings.DEFAULT_SESSION_GAP),
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
            val gap = sessionGap.stateIn(this, SharingStarted.Eagerly, Settings.DEFAULT_SESSION_GAP)
            launch {
                sessions.lastChange.filterNotNull().collect { change ->
                    val record = record(change)
                    writes.trySend(Write("a track change") { history.changed(record) })
                }
            }
            launch {
                var before: NowPlaying? = null
                // Read and written by the writes alone, which run one at a time.
                val playback = Playback()
                sessions.playing.collect { track ->
                    val at = wall.now()
                    val playedBefore = before
                    val same = { row: NowPlaying -> track != null && sameAsLast(row, track) }
                    // A track that plays on is updated; any other ends the one before and starts.
                    val continues = playedBefore != null && same(playedBefore)
                    if (playedBefore != null && !continues) {
                        writes.trySend(Write("a track stopping") { history.stopped(at) })
                    }
                    if (track != null) {
                        val gapNow = gap.value
                        val write = Write("a track played") { playback.write(track, at, gapNow, continues, same) }
                        writes.trySend(write)
                    }
                    before = track
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

    private fun record(change: SessionChange): TrackChangeRecord =
        TrackChangeRecord(
            player = change.track.player,
            changedAt = wallTime(change.changedAt),
            from = change.from?.let { TrackRef(it.title, it.mediaId) },
            to = TrackRef(change.track.title, change.track.mediaId),
            timeLeft = change.timeLeft,
            states =
                change.states.map { report ->
                    PlaybackStateRecord(
                        receivedBefore = (change.changedAt - report.at).milliseconds,
                        status = report.state.status.name,
                        position = report.state.position,
                        positionBefore = positionBefore(change.changedAt, report.state),
                        speed = report.state.speed,
                    )
                },
            sinceLastAction = null,
            learnedCrossfade = null,
        )

    /**
     * How long before [changedAt] [state]'s position was taken; null with no position or no time
     * for it.
     */
    private fun positionBefore(
        changedAt: Long,
        state: PlaybackInfo,
    ): Duration? {
        val known = state.position != null && state.updatedAt > 0
        return if (known) (changedAt - state.updatedAt).milliseconds else null
    }

    /** [at], on the [elapsed] clock, as a wall-clock time. */
    private fun wallTime(at: Long): Instant = wall.now() - (elapsed() - at).milliseconds

    /**
     * The playback now recorded: when it started, and whether its start was written. Until it is
     * (the write failed), each update of it is a start again, at that time, so the history never
     * takes an update for a row of an earlier playback that happens to hold the same track.
     */
    private inner class Playback {
        private var startedAt: Instant? = null
        private var written = false

        fun write(
            track: NowPlaying,
            at: Instant,
            gap: Duration,
            continues: Boolean,
            same: (NowPlaying) -> Boolean,
        ) {
            val start = startedAt
            if (continues && written && start != null) {
                history.continued(track, start, gap, same)
                return
            }
            val from = if (continues && start != null) start else at
            startedAt = from
            written = false
            history.started(track, from, gap, same)
            written = true
        }
    }

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
