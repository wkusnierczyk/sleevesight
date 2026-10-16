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
 * - [played]: each track the session layer saw play, in order, grouped in listening sessions: the
 *   default track list, "Played this session".
 * - [changed]: each change from one track to another, with the features skip detection will learn
 *   from per player.
 *
 * A track that starts more than [sessionGap] after the previous one should have ended (its start
 * plus its length, or its start when the player sent no length) begins a new listening session.
 *
 * Calls are blocking database calls: the app makes them off the main thread, from one writer.
 */
class SessionHistory(
    driver: SqlDriver,
    private val sessionGap: Duration = DEFAULT_SESSION_GAP,
) {
    private val queries: HistoryQueries = HistoryDatabase(driver).historyQueries

    /**
     * Adds [track], which began to play at [at], to the history, unless [sameTrack] says it is the
     * track added last and it is still the same listening session: the session layer shows a track
     * again after a restart, and an update of the same track (artists sent after the title, a new
     * length) is no new entry; the entry then takes the update. The art is not kept. Returns the
     * entry that now holds the track.
     */
    fun played(
        track: NowPlaying,
        at: Instant,
        sameTrack: (NowPlaying) -> Boolean,
    ): PlayedTrack =
        queries.transactionWithResult {
            val kept = track.copy(artUri = null)
            val last = queries.lastPlayed().executeAsOneOrNull()?.let(::playedTrack)
            val session =
                when {
                    last == null -> 1L
                    at - last.endsAt > sessionGap -> last.session + 1
                    else -> last.session
                }
            if (last != null && session == last.session && sameTrack(last.track)) {
                if (last.track != kept) update(last.id, kept)
                last.copy(track = kept)
            } else {
                queries.playedInsert(
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
                PlayedTrack(queries.lastInsertedId().executeAsOne(), session, at, kept)
            }
        }

    private fun update(
        id: Long,
        track: NowPlaying,
    ) {
        queries.playedUpdate(
            title = track.title,
            artists = track.artists,
            album = track.album,
            duration_ms = track.duration?.inWholeMilliseconds,
            media_id = track.mediaId,
            queue_item = track.queueItemId,
            id = id,
        )
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
                    position_before_ms = state.positionBefore.inWholeMilliseconds,
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
        )

    private fun trackChange(
        row: Track_change,
        states: List<Track_change_state>,
    ): TrackChangeRecord =
        TrackChangeRecord(
            player = row.player,
            changedAt = Instant.fromEpochMilliseconds(row.changed_at),
            timeLeft = row.time_left_ms?.milliseconds,
            states =
                states.map {
                    PlaybackStateRecord(
                        receivedBefore = it.received_before_ms.milliseconds,
                        status = it.status,
                        position = it.position_ms?.milliseconds,
                        positionBefore = it.position_before_ms.milliseconds,
                        speed = it.speed.toFloat(),
                    )
                },
            sinceLastAction = row.since_action_ms?.milliseconds,
            learnedCrossfade = row.learned_crossfade_ms?.milliseconds,
        )

    companion object {
        /**
         * How long a break in listening ends a listening session: longer than a pause to answer the
         * door or change trains, shorter than a meal or the gap between the morning's listening
         * and the evening's.
         */
        val DEFAULT_SESSION_GAP: Duration = 30.minutes
    }
}

/**
 * A track in the session history.
 *
 * @property session the listening session it played in, numbered from 1.
 * @property playedAt when the session layer first saw it play.
 * @property track the track as the player reported it then, without its art.
 */
data class PlayedTrack(
    val id: Long,
    val session: Long,
    val playedAt: Instant,
    val track: NowPlaying,
) {
    /** When it should have ended: its start plus its length, or its start when the player sent none. */
    val endsAt: Instant get() = playedAt + (track.duration ?: Duration.ZERO)
}

/**
 * A change from one track to another, with the features skip detection learns from (D15). A feature
 * that could not be measured, or that nothing supplies yet, is null.
 *
 * @property player the player whose track changed (the new track's).
 * @property changedAt when the change began, as the session layer timed it.
 * @property timeLeft the time the previous track had left at the change: its length less its
 *   position then; null when either is unknown, or on a switch from another player's track.
 * @property states the playback states the player reported shortly before the change, oldest first.
 * @property sinceLastAction the time since the user's last action in Sleevesight.
 * @property learnedCrossfade the crossfade learned from natural ends by then (D5).
 */
data class TrackChangeRecord(
    val player: String,
    val changedAt: Instant,
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
 * @property positionBefore how long before the change [position] was taken; negative if after it.
 * @property speed the playback speed: 1 for normal play, 0 when paused.
 */
data class PlaybackStateRecord(
    val receivedBefore: Duration,
    val status: String,
    val position: Duration?,
    val positionBefore: Duration,
    val speed: Float,
)
