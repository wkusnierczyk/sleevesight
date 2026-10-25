package app.sleevesight.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.store.history.HistoryDatabase
import app.sleevesight.store.history.HistoryQueries
import app.sleevesight.store.history.Played
import app.sleevesight.store.history.Track_change
import app.sleevesight.store.history.Track_change_state
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * The session history and the record of track changes (`design/architecture.md`, *Cache and
 * history* and *Learning skip detection*, D15), over a database of their own, apart from the
 * caches: [driver] is the Android driver in `:app`, with [HistoryDatabase.Schema] created on it,
 * or migrated to from an older version. Both stay on the phone, are never evicted by the caches'
 * size limit, and are cleared together ([clear]); what is older than the user's *Keep history for*
 * is deleted ([deleteOlderThan]).
 *
 * - The session history: each track the session layer saw play, in order, grouped in listening
 *   sessions: the default track list, "Played this session".
 * - [changed]: each change from one track to another, with the features skip detection will learn
 *   from per player.
 *
 * The caller says what happened to the history, and these are its rules:
 *
 * 1. [started]: a track began to play: a new one, a resume after a pause or a stop, or one found
 *    playing when the app starts. Only here is a listening session decided, by the session gap the
 *    caller passes (the user's *New session idle trigger*), so a new gap applies from
 *    the next start. The break is the time since the last row was last heard
 *    ([PlayedTrack.heardUntil]).
 *    - The same track as the last row, and a break of at most the gap: that row again, playing,
 *      begun again at the start's time ([PlayedTrack.resumedAt]).
 *    - Otherwise a new row: in the last row's session if the break is at most the gap, else in
 *      the next one.
 * 2. [continued]: the track of the last row plays on without a break, as the player updated it
 *    (artists sent after the title, an album, a new length): the row takes the update, however
 *    long it has played. No session is decided. If the last row does not hold that track (its
 *    start was never written, or its row has gone), this is a start, at the time the playback
 *    started, so the update is not lost.
 * 3. [stopped]: the last row stopped playing (paused, stopped, or another track began); the first
 *    stop is kept until it plays again.
 *
 * The art is never kept.
 *
 * Calls are blocking database calls, made off the main thread. [started], [continued] and [stopped]
 * come from one writer, in the order the tracks played, and [clear] and [deleteOlderThan] from the
 * same writer, so they are ordered with the writes. Each is one transaction.
 */
@Suppress("TooManyFunctions") // One for each way the history is written, read or deleted.
class SessionHistory(
    private val driver: SqlDriver,
) {
    private val queries: HistoryQueries = HistoryDatabase(driver).historyQueries

    /**
     * [track] began to play at [at] (rule 1), a listening session ending after a break longer than
     * [sessionGap]; [sameTrack] says whether a row holds it. Returns the row that now holds it.
     */
    fun started(
        track: NowPlaying,
        at: Instant,
        sessionGap: Duration,
        sameTrack: (NowPlaying) -> Boolean,
    ): PlayedTrack = queries.transactionWithResult { start(track.copy(artUri = null), at, sessionGap, sameTrack) }

    /**
     * [track], the last row's, plays on without a break, updated (rule 2); [sameTrack] says
     * whether a row holds it. [at] is when this playback started: if no row holds the track, it
     * starts then, by [sessionGap]. Returns the row that now holds it.
     */
    fun continued(
        track: NowPlaying,
        at: Instant,
        sessionGap: Duration,
        sameTrack: (NowPlaying) -> Boolean,
    ): PlayedTrack =
        queries.transactionWithResult {
            val kept = track.copy(artUri = null)
            val last = queries.last()
            if (last != null && sameTrack(last.track)) {
                queries.playing(last, kept)
            } else {
                start(kept, at, sessionGap, sameTrack)
            }
        }

    private fun start(
        kept: NowPlaying,
        at: Instant,
        sessionGap: Duration,
        sameTrack: (NowPlaying) -> Boolean,
    ): PlayedTrack {
        val last = queries.last() ?: return queries.insert(kept, at, session = 1)
        val withinGap = at - last.heardUntil <= sessionGap
        return when {
            withinGap && sameTrack(last.track) -> queries.resumed(last, kept, at)
            withinGap -> queries.insert(kept, at, last.session)
            else -> queries.insert(kept, at, last.session + 1)
        }
    }

    /** The last row stopped playing at [at] (rule 3). */
    fun stopped(at: Instant) {
        queries.playedStopped(at.toEpochMilliseconds())
    }

    /** The number of the latest listening session, null when nothing has played (#239, `SessionFacts`). */
    fun latestSessionNumber(): Long? = queries.lastPlayed().executeAsOneOrNull()?.session

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

    /**
     * A page of the track changes recorded, newest first: at most [limit], older than the page that
     * gave [before] as its [TrackChangePage.next], or the latest with none; and only those recorded
     * after the change [after] names ([lastChangeId]), all with 0. For the diagnostics screen (#54)
     * and skip detection's learning (D5, D15). Pages are by the order the changes were written, so
     * changes written meanwhile do not shift a later page.
     */
    fun changes(
        limit: Int,
        before: Long? = null,
        after: Long = 0,
    ): TrackChangePage {
        require(limit > 0) { "A page holds at least one change, was $limit" }
        return queries.transactionWithResult {
            // One more than asked, to know whether an older page follows.
            val rows = queries.trackChangesBefore(before ?: Long.MAX_VALUE, after, limit + 1L).executeAsList()
            val page = rows.take(limit)
            val states =
                if (page.isEmpty()) {
                    emptyMap()
                } else {
                    queries
                        .statesBetween(page.last().id, page.first().id)
                        .executeAsList()
                        .groupBy(Track_change_state::change_id)
                }
            TrackChangePage(
                changes = page.map { trackChange(it, states[it.id].orEmpty()) },
                next = page.lastOrNull()?.id?.takeIf { rows.size > limit },
            )
        }
    }

    /**
     * The id of the newest change recorded, 0 with none: a mark that [changes] can read after. Ids
     * only grow, through *Keep history for*'s deletions and *Clear history* (`AUTOINCREMENT`), and do
     * not depend on the clock.
     */
    fun lastChangeId(): Long = queries.trackChangeLastId().executeAsOne()

    /**
     * The highest id ever given to a change in this database, 0 with none: `AUTOINCREMENT`'s
     * sequence, which [clear] and [deleteOlderThan] leave as it is, so no mark taken from
     * [lastChangeId] is above it while the database lasts. A mark above it is from an earlier
     * database, replaced since (Android deletes a corrupt database file and the app creates it
     * anew), whose ids started again from 1. Read from `sqlite_sequence` in SQL of its own, since
     * SQLDelight does not know that table's columns.
     */
    fun changeSequence(): Long =
        driver
            .executeQuery(
                identifier = null,
                sql = "SELECT seq FROM sqlite_sequence WHERE name = 'track_change'",
                mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0 else 0) },
                parameters = 0,
            ).value

    /**
     * Deletes what is older than [lifetime], the user's *Keep history for*, in one transaction: the
     * tracks that began to play, and the track changes that began, more than [lifetime] before
     * [now]. The track added last is kept while it may still be playing, so a stream that plays
     * across the cutoff keeps its row, and its stop.
     *
     * By when a track began rather than when it was last heard: every row has it, and it is the
     * order the history is in, so what goes is its oldest part; the two times differ by how long a
     * track played, against a limit of days. [now] is the caller's: a clock set ahead deletes by
     * it, so the caller checks the clock first (`HistoryRecorder`).
     */
    fun deleteOlderThan(
        lifetime: Duration,
        now: Instant,
    ) {
        queries.deleteBefore((now - lifetime).toEpochMilliseconds())
    }

    /** Deletes the session history and the record of track changes together, in one transaction. */
    fun clear() {
        queries.clear()
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

/** [last], holding [track] and playing again from [at]. */
private fun HistoryQueries.resumed(
    last: PlayedTrack,
    track: NowPlaying,
    at: Instant,
): PlayedTrack {
    playedResumed(
        title = track.title,
        artists = track.artists,
        album = track.album,
        duration_ms = track.duration?.inWholeMilliseconds,
        media_id = track.mediaId,
        queue_item = track.queueItemId,
        resumed_at = at.toEpochMilliseconds(),
        id = last.id,
    )
    return last.copy(track = track, stoppedAt = null, resumedAt = at)
}

/** [last], holding [track] and playing on. */
private fun HistoryQueries.playing(
    last: PlayedTrack,
    track: NowPlaying,
): PlayedTrack {
    if (last.track != track || last.stoppedAt != null) update(last.id, track)
    return last.copy(track = track, stoppedAt = null)
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
        resumedAt = row.resumed_at?.let(Instant::fromEpochMilliseconds),
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
 * @property resumedAt when the app last saw it begin to play again in this row, with the same lag:
 *   resumed after a pause or a stop, or found playing again after a restart; null if it has not, or
 *   if the row was written before the history kept it (version 1 of its schema).
 */
data class PlayedTrack(
    val id: Long,
    val session: Long,
    val playedAt: Instant,
    val track: NowPlaying,
    val stoppedAt: Instant? = null,
    val resumedAt: Instant? = null,
) {
    /**
     * When it was last heard: when it stopped; failing that (it plays, or the process ended while it
     * played), when it would have ended had it played once through from when it last began to play
     * ([resumedAt], else [playedAt]): that time plus its length, or that time alone when the player
     * sent none.
     *
     * A whole length from the resume, not the part left after what was heard before the pause: no
     * position reaches the history, and the position at a resume may be anywhere (a seek while
     * paused, a restart from the top), so a whole length is the most the time since the resume
     * allows. The cost is a break that may be counted short, so a later start may stay in a
     * session it would otherwise have begun anew.
     */
    val heardUntil: Instant get() = stoppedAt ?: ((resumedAt ?: playedAt) + (track.duration ?: Duration.ZERO))
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
 * A page of the track changes, newest first ([SessionHistory.changes]).
 *
 * @property next what to pass as `before` for the next, older page; null when there is none.
 */
data class TrackChangePage(
    val changes: List<TrackChangeRecord>,
    val next: Long?,
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
