package app.sleevesight.store

import app.cash.sqldelight.db.SqlDriver
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.store.history.HistoryDatabase
import app.sleevesight.store.history.HistoryQueries
import app.sleevesight.store.history.Played
import app.sleevesight.store.history.Track_change
import app.sleevesight.store.history.Track_change_state
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The session history and the record of track changes (`design/architecture.md`, *Cache and
 * history* and *Learning skip detection*, D15), over a database of their own, apart from the
 * caches: [driver] is the Android driver in `:app`, with [HistoryDatabase.Schema] created on it.
 * Both stay on the phone, are never evicted by the caches' size limit, and are cleared together
 * ([clear]).
 *
 * - The session history: each track the session layer saw play, in order, grouped in listening
 *   sessions: the default track list, "Played this session".
 * - [changed]: each change from one track to another, with the features skip detection will learn
 *   from per player.
 *
 * The caller says what happened to the history, and these are its rules:
 *
 * 1. [started]: a track began to play: a new one, a resume after a pause or a stop, or one found
 *    playing when the app starts. Only here is a listening session decided. The break is the time
 *    since the last row was last heard ([PlayedTrack.heardUntil]).
 *    - The same track as the last row, and a break of at most [sessionGap]: that row again, playing.
 *    - Otherwise a new row: in the last row's session if the break is at most [sessionGap], else
 *      in the next one.
 * 2. [continued]: the track of the last row plays on without a break, as the player updated it
 *    (artists sent after the title, an album, a new length): the row takes the update, however
 *    long it has played. No session is decided. If the last row is not that track (its write
 *    failed), this is a start.
 * 3. [stopped]: the last row stopped playing (paused, stopped, or another track began); the first
 *    stop is kept until it plays again.
 *
 * The art is never kept.
 *
 * Calls are blocking database calls: the app makes them off the main thread, from one writer.
 */
class SessionHistory(
    driver: SqlDriver,
    private val sessionGap: Duration = DEFAULT_SESSION_GAP,
) {
    private val queries: HistoryQueries = HistoryDatabase(driver).historyQueries

    /**
     * [track] began to play at [at] (rule 1); [sameTrack] says whether a row holds it. Returns the
     * row that now holds it.
     */
    fun started(
        track: NowPlaying,
        at: Instant,
        sameTrack: (NowPlaying) -> Boolean,
    ): PlayedTrack = queries.transactionWithResult { start(track.copy(artUri = null), at, sameTrack) }

    /**
     * [track], the last row's, plays on without a break, updated (rule 2); [sameTrack] says
     * whether a row holds it. [at] is when this playback started: if no row holds the track, it
     * starts then. Returns the row that now holds it.
     */
    fun continued(
        track: NowPlaying,
        at: Instant,
        sameTrack: (NowPlaying) -> Boolean,
    ): PlayedTrack =
        queries.transactionWithResult {
            val kept = track.copy(artUri = null)
            val last = queries.last()
            if (last != null && sameTrack(last.track)) {
                playing(last, kept)
            } else {
                start(kept, at, sameTrack)
            }
        }

    private fun start(
        kept: NowPlaying,
        at: Instant,
        sameTrack: (NowPlaying) -> Boolean,
    ): PlayedTrack {
        val last = queries.last() ?: return queries.insert(kept, at, session = 1)
        val withinGap = at - last.heardUntil <= sessionGap
        return when {
            withinGap && sameTrack(last.track) -> playing(last, kept)
            withinGap -> queries.insert(kept, at, last.session)
            else -> queries.insert(kept, at, last.session + 1)
        }
    }

    /** [last], holding [track] and playing. */
    private fun playing(
        last: PlayedTrack,
        track: NowPlaying,
    ): PlayedTrack {
        if (last.track != track || last.stoppedAt != null) queries.update(last.id, track)
        return last.copy(track = track, stoppedAt = null)
    }

    /** The last row stopped playing at [at] (rule 3). */
    fun stopped(at: Instant) {
        queries.playedStopped(at.toEpochMilliseconds())
    }

    /** The tracks of the latest listening session, in the order they played; empty when there are none. */
    fun latestSession(): List<PlayedTrack> {
        val last = queries.lastPlayed().executeAsOneOrNull() ?: return emptyList()
        return session(last.session)
    }

    /** The tracks of listening session [number], in the order they played. */
    fun session(number: Long): List<PlayedTrack> = queries.playedInSession(number).executeAsList().map(::playedTrack)

    /** Records [change], with the playback states before it in the order they came. */
    fun changed(change: TrackChangeRecord) {
        queries.transaction {
            queries.trackChangeInsert(
                player = change.player,
                changed_at = change.changedAt.toEpochMilliseconds(),
                from_title = change.from?.title,
                from_media_id = change.from?.mediaId,
                to_title = change.to.title,
                to_media_id = change.to.mediaId,
                time_left_ms = change.timeLeft?.inWholeMilliseconds,
                since_action_ms = change.sinceLastAction?.inWholeMilliseconds,
                learned_crossfade_ms = change.learnedCrossfade?.inWholeMilliseconds,
            )
            val id = queries.lastInsertedId().executeAsOne()
            change.states.forEachIndexed { seq, state ->
                queries.stateInsert(
                    change_id = id,
                    seq = seq.toLong(),
                    received_before_ms = state.receivedBefore.inWholeMilliseconds,
                    status = state.status,
                    position_ms = state.position?.inWholeMilliseconds,
                    position_before_ms = state.positionBefore?.inWholeMilliseconds,
                    speed = state.speed.toDouble(),
                )
            }
        }
    }

    /** Every track change recorded, oldest first: for the diagnostics screen and, later, learning (D15). */
    fun changes(): List<TrackChangeRecord> {
        val states = queries.states().executeAsList().groupBy(Track_change_state::change_id)
        return queries.trackChanges().executeAsList().map { trackChange(it, states[it.id].orEmpty()) }
    }

    /** Deletes the session history and the record of track changes together, in one transaction. */
    fun clear() {
        queries.clear()
    }

    companion object {
        /**
         * How long a break in listening ends a listening session: longer than a pause to answer the
         * door or change trains, shorter than a meal or the gap between the morning's listening
         * and the evening's.
         */
        val DEFAULT_SESSION_GAP: Duration = 30.minutes
    }
}

private fun HistoryQueries.last(): PlayedTrack? = lastPlayed().executeAsOneOrNull()?.let(::playedTrack)

private fun HistoryQueries.insert(
    kept: NowPlaying,
    at: Instant,
    session: Long,
): PlayedTrack {
    playedInsert(
        session = session,
        played_at = at.toEpochMilliseconds(),
        player = kept.player,
        title = kept.title,
        artists = kept.artists,
        album = kept.album,
        duration_ms = kept.duration?.inWholeMilliseconds,
        media_id = kept.mediaId,
        queue_item = kept.queueItemId,
    )
    return PlayedTrack(lastInsertedId().executeAsOne(), session, at, kept)
}

private fun HistoryQueries.update(
    id: Long,
    track: NowPlaying,
) {
    playedUpdate(
        title = track.title,
        artists = track.artists,
        album = track.album,
        duration_ms = track.duration?.inWholeMilliseconds,
        media_id = track.mediaId,
        queue_item = track.queueItemId,
        id = id,
    )
}

private fun playedTrack(row: Played): PlayedTrack =
    PlayedTrack(
        id = row.id,
        session = row.session,
        playedAt = Instant.fromEpochMilliseconds(row.played_at),
        track =
            NowPlaying(
                title = row.title,
                artists = row.artists,
                album = row.album,
                duration = row.duration_ms?.milliseconds,
                player = row.player,
                mediaId = row.media_id,
                queueItemId = row.queue_item,
            ),
        stoppedAt = row.stopped_at?.let(Instant::fromEpochMilliseconds),
    )

private fun trackChange(
    row: Track_change,
    states: List<Track_change_state>,
): TrackChangeRecord =
    TrackChangeRecord(
        player = row.player,
        changedAt = Instant.fromEpochMilliseconds(row.changed_at),
        from = row.from_title?.let { TrackRef(it, row.from_media_id) },
        to = TrackRef(row.to_title, row.to_media_id),
        timeLeft = row.time_left_ms?.milliseconds,
        states =
            states.map {
                PlaybackStateRecord(
                    receivedBefore = it.received_before_ms.milliseconds,
                    status = it.status,
                    position = it.position_ms?.milliseconds,
                    positionBefore = it.position_before_ms?.milliseconds,
                    speed = it.speed.toFloat(),
                )
            },
        sinceLastAction = row.since_action_ms?.milliseconds,
        learnedCrossfade = row.learned_crossfade_ms?.milliseconds,
    )

/**
 * A track in the session history.
 *
 * @property session the listening session it played in, numbered from 1.
 * @property playedAt when the app first saw it play, once the player's reports had settled: up to
 *   the settle window after they came, longer while it buffered first. A change's precise time is
 *   its [TrackChangeRecord.changedAt].
 * @property track the track as the player last reported it, without its art.
 * @property stoppedAt when the app first saw it stop playing, with the same lag as [playedAt]; null
 *   while it plays, or if the process ended while it played.
 */
data class PlayedTrack(
    val id: Long,
    val session: Long,
    val playedAt: Instant,
    val track: NowPlaying,
    val stoppedAt: Instant? = null,
) {
    /**
     * When it was last heard: when it stopped; failing that, when it should have ended (its start
     * plus its length, or its start when the player sent none).
     */
    val heardUntil: Instant get() = stoppedAt ?: (playedAt + (track.duration ?: Duration.ZERO))
}

/**
 * A change from one track to another, with the features skip detection learns from (D15). A feature
 * that could not be measured, or that nothing supplies yet, is null.
 *
 * @property player the player whose track changed (the new track's).
 * @property changedAt when the change began, as the session layer timed it.
 * @property from the track it left; null if none is known.
 * @property to the track it entered.
 * @property timeLeft the time the previous track had left at the change: its length less its
 *   position then; null when either is unknown, or on a switch from another player's track.
 * @property states the playback states the player reported shortly before the change, oldest first.
 * @property sinceLastAction the time since the user's last action in Sleevesight.
 * @property learnedCrossfade the crossfade learned from natural ends by then (D5).
 */
data class TrackChangeRecord(
    val player: String,
    val changedAt: Instant,
    val from: TrackRef?,
    val to: TrackRef,
    val timeLeft: Duration?,
    val states: List<PlaybackStateRecord>,
    val sinceLastAction: Duration?,
    val learnedCrossfade: Duration?,
)

/**
 * A playback state reported before a track change.
 *
 * @property receivedBefore how long before the change it came.
 * @property status the session layer's name for its state: `PLAYING`, `PAUSED`, and so on.
 * @property position the position it reported, if any.
 * @property positionBefore how long before the change [position] was taken; negative if after it,
 *   null with no position or no time for it.
 * @property speed the playback speed: 1 for normal play, 0 when paused.
 */
data class PlaybackStateRecord(
    val receivedBefore: Duration,
    val status: String,
    val position: Duration?,
    val positionBefore: Duration?,
    val speed: Float,
)

/** A track named in a track change: its title, and its media id when the player sends one. */
data class TrackRef(
    val title: String,
    val mediaId: String?,
)
