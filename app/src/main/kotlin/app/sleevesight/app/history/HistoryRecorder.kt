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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
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
 *     listening session begins, by the [sessionGap] when it is written (the user's *Start a new
 *     session after a break of*: the queue waits for the settings' first read);
 *   - none: the one before *stopped* (paused, stopped, the session layer stopped).
 * - **Times in the history** are when the recorder sees them: a track's start when it is first seen
 *   playing, its stop when it is first seen to stop, both as [SessionRepository.playing] publishes
 *   them, after the player's reports have settled. So they lag the reports by up to the settle
 *   window, and a track that buffers before it plays starts once it plays. The precise time of a
 *   change is its record's `changedAt`; its `from` and `to` name the tracks it links.
 * - **Each track change** ([SessionRepository.lastChange]) with its features: the time the
 *   previous track had left ([SessionChange.timeLeft]), the playback states before it, the player,
 *   the crossfade skip detection had learned when it judged the change
 *   ([SessionChange.learnedCrossfade]), and the time since the user's last action in Sleevesight
 *   before it, from [lastAction] (`UserActions`: a tap on Write taken; the transport controls in
 *   Phase 2), null with none since the process started.
 * - **An ad told late** ([SessionRepository.withdrawn], #880): published as a track before the queue
 *   that names it an ad came, so it may have a row and a change into it. Both are deleted, the row
 *   only when it was started for that play (not a row it resumed), the change only when it is the
 *   last recorded and leads into that play; and the playback is forgotten, so the next track starts
 *   afresh.
 *
 * The session layer's times are on the [elapsed] clock (`SystemClock.elapsedRealtime`), which
 * restarts with the phone, so they are kept as wall-clock times from [wall] and as offsets from the
 * change. Writes go through one queue, in the order they were seen, each on [io]; a write that fails
 * (the disk full, say) is reported to [warn] and dropped, never thrown, so it does not end the run.
 * After a failed start, the track's next update starts it again, at its original time, rather than
 * updating the last row, which may be an earlier playback of the same track.
 *
 * *Clear history* ([clear]) and the deletion of old rows ([deleteOlderThan]) go through the same
 * queue, so each runs after every write queued before it and before any queued after: a clear is
 * never undone by a write of something seen before it. A clear also forgets the playback being
 * recorded, so the track playing then comes back, as a new row, only with its next value, and tells
 * [onCleared] (skip detection forgets what it learned from the changes deleted).
 *
 * **A clock that jumps.** Old rows are deleted by the [wall] clock, so a clock set ahead would
 * delete history early. The recorder notes how far the wall clock is from the [elapsed] clock,
 * which nothing sets, when it is made, at the process's start; each deletion checks them again as
 * it runs, and is skipped while they have moved apart by more than [CLOCK_MARGIN], ahead or back.
 * Once the clock is set right, the next deletion runs: no deletion ever runs by a moved clock, and
 * a check that stayed tripped until the next start would only keep entries past the user's *Keep
 * history for* for as long as the process lives. A start takes the clock as it finds it, so a jump
 * made while the process was not running, or across a restart of the phone, is not caught.
 */
@Suppress("LongParameterList") // What it records from and to, the user's gap and actions; the rest are test seams.
class HistoryRecorder(
    private val sessions: SessionRepository,
    private val history: SessionHistory,
    private val io: CoroutineContext,
    private val sessionGap: Flow<Duration> = flowOf(Settings.DEFAULT_SESSION_GAP),
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val wall: Clock = Clock.System,
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
    private val lastAction: (Long) -> Long? = { null },
    private val onCleared: () -> Unit = {},
) {
    // The collectors only queue, so they never suspend: a state flow's value is not missed while a
    // write is in progress. Read by [run] alone, which is called once.
    private val writes = Channel<Write>(Channel.UNLIMITED)

    // Read and written by the writes alone, which run one at a time.
    private val playback = Playback()

    // The last change recorded, by the play it led into ([NowPlaying.play]) and its id, for an ad
    // told late (#880). Read and written by the writes alone.
    private var lastInto: Pair<Long?, Long>? = null

    /** The wall clock less the [elapsed] one when the recorder was made: what a jump moves. */
    private val clockOffset = wall.now() - elapsed().milliseconds

    /** Records until the calling coroutine is cancelled. Called once, for the process. */
    suspend fun run(): Unit =
        coroutineScope {
            // Null until the settings are first read: the first start waits for the user's gap.
            val gap = sessionGap.stateIn(this, SharingStarted.Eagerly, null)
            launch {
                sessions.lastChange.filterNotNull().collect { change ->
                    val record = record(change)
                    val into = change.track.play
                    writes.trySend(Write.record("a track change") { lastInto = into to history.changed(record) })
                }
            }
            launch {
                sessions.withdrawn.filterNotNull().collect { ad ->
                    writes.trySend(Write.record("an ad taken back") { withdraw(ad) })
                }
            }
            launch {
                var before: NowPlaying? = null
                sessions.playing.collect { track ->
                    val at = wall.now()
                    val playedBefore = before
                    val same = { row: NowPlaying -> track != null && sameAsLast(row, track) }
                    // A track that plays on is updated; any other ends the one before and starts.
                    val continues = playedBefore != null && same(playedBefore)
                    if (playedBefore != null && !continues) {
                        writes.trySend(Write.record("a track stopping") { history.stopped(at) })
                    }
                    if (track != null) {
                        val write = { given: Duration -> playback.write(track, at, given, continues) }
                        writes.trySend(Write.record("a track played", write))
                    }
                    before = track
                }
            }
            for (write in writes) {
                val gapNow = gap.filterNotNull().first()
                val done =
                    try {
                        withContext(io) { write.run(gapNow) }
                        true
                    } catch (_: SQLException) {
                        // No exception text: a database message may name a path or a track.
                        warn(write.failure)
                        false
                    }
                write.done?.complete(done)
            }
        }

    // [ad], recorded as a track before it was told to be an ad (#880): its row and the change into it go.
    private fun withdraw(ad: NowPlaying) {
        val play = ad.play ?: return
        val change = lastInto?.takeIf { it.first == play }?.second
        history.withdraw(playback.rowOf(play), change)
        if (change != null) lastInto = null
        playback.forget()
    }

    /**
     * *Clear history*: deletes the session history and the track changes, after every write queued
     * before it, and forgets the playback being recorded. Returns whether it succeeded; a failure is
     * reported to [warn] without its text, never thrown. Waits for [run] to reach it.
     */
    suspend fun clear(): Boolean =
        queued("Could not clear the session history") {
            history.clear()
            playback.forget()
            onCleared()
        }

    /**
     * Deletes what is older than the [lifetime] it returns when the deletion runs, in its place in
     * the queue ([SessionHistory.deleteOlderThan]), by the [wall] clock then: so a lifetime made
     * longer, or *Forever* (null), while it waits is never undercut. With *Forever* nothing is
     * deleted and the clock is not checked; otherwise skipped while the wall clock has moved (the
     * class's KDoc), which is reported to [warn]. Returns whether it succeeded or was skipped; a
     * failure is reported to [warn] without its text, never thrown.
     */
    suspend fun deleteOlderThan(lifetime: () -> Duration?): Boolean =
        queued("Could not delete old entries from the session history") {
            val kept = lifetime() ?: return@queued
            val now = wall.now()
            if ((now - elapsed().milliseconds - clockOffset).absoluteValue > CLOCK_MARGIN) {
                warn("The clock moved: old entries in the session history are kept until it is set right")
            } else {
                history.deleteOlderThan(kept, now)
            }
        }

    private suspend fun queued(
        failure: String,
        run: () -> Unit,
    ): Boolean {
        val done = CompletableDeferred<Boolean>()
        writes.trySend(Write(failure, { run() }, done))
        return done.await()
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
            sinceLastAction = lastAction(change.changedAt)?.let { (change.changedAt - it).milliseconds },
            learnedCrossfade = change.learnedCrossfade,
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
     *
     * Also the play ([NowPlaying.play]) whose track the last row written holds: a row read back
     * carries no play, so a row written for the track's own play is matched by the play, as the
     * session layer judged it (an album filled in during the play is no new row); any other row
     * (another play, or one from before the process started or a [forget]) by its fields, so the
     * same track found again after a restart resumes its row. A row the play took over by its
     * fields keeps, as a witness ([witness]), those the report that took it over left out (the
     * play never learned them): a later report of the play that conflicts with them is another
     * row, judged as the session layer judges a play from when the row was taken over, so an album
     * filled in within the first second (`TrackIdentity.FILL_IN`) is the same row and fills in the
     * witness too. Fields the report carried are the session layer's to judge.
     */
    private inner class Playback {
        private var startedAt: Instant? = null
        private var written = false
        private var rowPlay: Long? = null

        // The last row written, when it was started (not resumed or updated) for [rowPlay].
        private var newRow: Long? = null
        private var witness: NowPlaying? = null
        private var witnessAt: Instant? = null

        /** When the write in progress was seen. */
        private var writeAt: Instant? = null

        fun write(
            track: NowPlaying,
            at: Instant,
            gap: Duration,
            continues: Boolean,
        ) {
            byPlay = false
            byFields = null
            writeAt = at
            val same = { row: NowPlaying -> matches(row, track) }
            // The play reports what its row's witness rules out: that row stopped, and another starts.
            val ruledOut = continues && witness?.let { witnessHolds(it, track) } == false
            if (ruledOut) history.stopped(at)
            val goesOn = continues && !ruledOut
            val start = startedAt
            if (goesOn && written && start != null) {
                history.continued(track, start, gap, same)
                if (rowPlay != track.play) newRow = null
                wrote(track)
                return
            }
            val from = if (goesOn && start != null) start else at
            startedAt = from
            written = false
            val row = history.started(track, from, gap, same)
            written = true
            // A row inserted now (not one resumed), or the one inserted for this play before.
            val inserted = row.playedAt.toEpochMilliseconds() == from.toEpochMilliseconds() && row.resumedAt == null
            newRow = row.id.takeIf { inserted || (it == newRow && rowPlay == track.play) }
            wrote(track)
        }

        /** The last row, when it was started for [play] and not resumed: what an ad told late takes back. */
        fun rowOf(play: Long): Long? = newRow?.takeIf { rowPlay == play }

        // How the last row was last matched, as the history asked: by the play, by its fields, or not.
        private var byPlay = false
        private var byFields: NowPlaying? = null

        /** Whether [row], the last, holds [track]: by the play, unless its witness rules it out; else by the fields. */
        private fun matches(
            row: NowPlaying,
            track: NowPlaying,
        ): Boolean {
            val ownPlay = rowPlay != null && rowPlay == track.play
            byPlay = ownPlay && witness?.let { witnessHolds(it, track) } != false
            byFields = null
            return when {
                byPlay -> sameAsLast(row.copy(play = track.play), track)
                ownPlay -> false
                else ->
                    sameAsLast(row, track).also { same ->
                        // Of the row, what the report left out: what the play never learned.
                        if (same) {
                            byFields =
                                row.copy(
                                    artists = row.artists.takeIf { track.artists.isEmpty() }.orEmpty(),
                                    album = row.album?.takeIf { track.album == null },
                                    mediaId = row.mediaId?.takeIf { track.mediaId == null },
                                )
                        }
                    }
            }
        }

        /**
         * Whether [track] agrees with the [held] witness, as the session layer judges a play, from
         * when the row was taken over.
         */
        private fun witnessHolds(
            held: NowPlaying,
            track: NowPlaying,
        ): Boolean {
            val since = writeAt?.let { now -> witnessAt?.let { now - it } } ?: Duration.INFINITE
            return TrackIdentity.continues(held, track, since)
        }

        /**
         * [track] written: a row matched by the play keeps its witness, filled in by [track] as
         * the play is; any other is the play's from now on, with a witness if taken over by fields.
         */
        private fun wrote(track: NowPlaying) {
            val held = witness
            if (byPlay) {
                witness = held?.let { TrackIdentity.merged(it, track.copy(play = null)) }
            } else {
                witness = byFields
                witnessAt = writeAt
            }
            rowPlay = track.play
        }

        /** The history was cleared: the playback's next value starts it again, at that value's time. */
        fun forget() {
            startedAt = null
            written = false
            rowPlay = null
            newRow = null
            witness = null
        }
    }

    /**
     * A write in the queue: [run] with the session gap, [failure] reported if it fails, and [done]
     * told whether it succeeded, when someone waits for it.
     */
    private class Write(
        val failure: String,
        val run: (Duration) -> Unit,
        val done: CompletableDeferred<Boolean>? = null,
    ) {
        companion object {
            fun record(
                what: String,
                run: (Duration) -> Unit,
            ) = Write("Could not record $what in the session history", run)
        }
    }

    private companion object {
        const val TAG = "Sleevesight"

        /**
         * How far the wall clock may move against the [elapsed] clock before it counts as set: an
         * hour, far more than a network time correction, far less than a lifetime of days.
         */
        val CLOCK_MARGIN: Duration = 1.hours

        /**
         * Whether [last], the track added last, is [track] again: the same track as the session
         * layer decides ([TrackIdentity]), with the same title ([TrackIdentity.playsOn]): a title
         * changing under one media id (a stream) is a new track.
         */
        fun sameAsLast(
            last: NowPlaying,
            track: NowPlaying,
        ): Boolean = TrackIdentity.playsOn(last, track)
    }
}
