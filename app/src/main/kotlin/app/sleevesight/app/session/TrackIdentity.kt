package app.sleevesight.app.session

import app.sleevesight.core.model.NowPlaying

/**
 * Whether two reports are the same track, for skip detection and the session history
 * (`design/architecture.md`, *Session layer*; `design/design.md`, *Track changes*):
 *
 * 1. tracks of different players differ;
 * 2. the media id decides when both send one;
 * 3. else two albums, both known and different, are two recordings (a studio and a live
 *    "Sundown"): different tracks, whatever their titles;
 * 4. else title and artists, where a report without artists matches any: a player may send the
 *    title first and the artists a moment later (YouTube Music at the start of a session). An
 *    album filled in later is an update.
 *
 * The active queue item does not decide (2026-10-19). Sleevesight cares what is playing (the
 * recording: its title, artists and album), not how many times: a song played again at once
 * counted as one play costs nothing, since no insight is written twice for one track without a
 * tap, while a false change costs a second history row and a false skip that skip detection
 * learns from. And the item a player reports cannot always be trusted to belong to the metadata
 * beside it: a player may send a new track's metadata before its state, so the item still names
 * the track before, and a session attached between the two has nothing to tell it by. The item
 * is still received, carried in [NowPlaying.queueItemId] and recorded; it only finds the next
 * track in the queue (`NextInQueue`).
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
            a.album != null && b.album != null && a.album != b.album -> false
            else -> a.title == b.title && (a.artists == b.artists || a.artists.isEmpty() || b.artists.isEmpty())
        }

    /**
     * Whether [track] is [last] playing on: the same track ([same]) with the same title. A new
     * length of it, or artists or an album filled in, plays on (and any new album under one media
     * id); a title changing under one media id (a stream's next song) does not. The session
     * history's rule for a row that continues (`HistoryRecorder.sameAsLast`) and the pipeline's
     * for its insight (`sameInsightTrack`).
     */
    fun playsOn(
        last: NowPlaying,
        track: NowPlaying,
    ): Boolean = same(last, track) && last.title == track.title

    /**
     * The report to compare a new one with, after [last]: [last], unless it has no artists and
     * [withArtists], the last report of the same play that had them, is known. A report without
     * artists matches any ([same]), so comparing with it alone would take X, then the title with
     * no artists, then Y, for one play.
     */
    fun reference(
        last: NowPlaying,
        withArtists: NowPlaying?,
    ): NowPlaying = if (last.artists.isEmpty() && withArtists != null) withArtists else last

    /**
     * The last report with artists of the play [track] belongs to, the one before being
     * [withArtists]: [track] when it has artists; else, while it [continues] that play,
     * [withArtists]; else none (no track, or another play begun without artists).
     */
    fun withArtists(
        withArtists: NowPlaying?,
        track: NowPlaying?,
        continues: Boolean,
    ): NowPlaying? =
        when {
            track == null -> null
            track.artists.isNotEmpty() -> track
            continues -> withArtists
            else -> null
        }
}
