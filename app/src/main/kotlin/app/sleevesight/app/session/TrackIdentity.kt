package app.sleevesight.app.session

import app.sleevesight.core.model.NowPlaying

/**
 * Whether two reports are the same track, for skip detection and the session history
 * (`design/architecture.md`, *Session layer*; `design/design.md`, *Track changes*):
 *
 * 1. tracks of different players differ;
 * 2. the media id decides when both send one;
 * 3. else title and artists, where a report without artists matches any: a player may send the
 *    title first and the artists a moment later (YouTube Music at the start of a session).
 *
 * The active queue item does not decide (2026-10-19). Sleevesight cares what is playing, not how
 * many times: a song played again at once counted as one play costs nothing, since no insight is
 * written twice for one track without a tap, while a false change costs a second history row and a
 * false skip that skip detection learns from. And the item a player reports cannot always be
 * trusted to belong to the metadata beside it: a player may send a new track's metadata before
 * its state, so the item still names the track before, and a session attached between the two
 * has nothing to tell it by. The item is still received, carried in [NowPlaying.queueItemId] and
 * recorded; it only finds the next track in the queue (`NextInQueue`).
 *
 * So a new length or new art for the same track is an update, not a change; YouTube Music sends
 * a track's length several times over. A song repeated at once is the same track, unless the
 * player gives it a new media id. The pipeline's lookup key is separate and unaffected.
 */
object TrackIdentity {
    fun same(
        a: NowPlaying,
        b: NowPlaying,
    ): Boolean =
        when {
            a.player != b.player -> false
            a.mediaId != null && b.mediaId != null -> a.mediaId == b.mediaId
            else -> a.title == b.title && (a.artists == b.artists || a.artists.isEmpty() || b.artists.isEmpty())
        }

    /**
     * Whether [track] is [last] playing on: the same track ([same]) with the same title. A new
     * length, album or artists of it plays on; a title changing under one media id (a stream's
     * next song) does not. The session history's rule for a row that continues
     * (`HistoryRecorder.sameAsLast`) and the pipeline's for its insight (`sameInsightTrack`).
     */
    fun playsOn(
        last: NowPlaying,
        track: NowPlaying,
    ): Boolean = same(last, track) && last.title == track.title
}
