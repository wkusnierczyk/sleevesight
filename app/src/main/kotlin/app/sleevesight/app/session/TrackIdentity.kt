package app.sleevesight.app.session

import app.sleevesight.core.model.NowPlaying

/**
 * Whether two reports are the same track, for skip detection and the session history
 * (`design/architecture.md`, *Session layer*; `design/design.md`, *Track changes*):
 *
 * 1. tracks of different players differ;
 * 2. the media id decides when both send one;
 * 3. else the active queue item, when both have one (the session's −1 is already null in
 *    `NowPlaying`): a song queued twice is two tracks, and the same item filled in with its
 *    artists or album is one;
 * 4. else title and artists, where a report without artists matches any: a player may send the
 *    title first and the artists a moment later (YouTube Music at the start of a session).
 *
 * So a new length or new art for the same track is an update, not a change; YouTube Music sends
 * a track's length several times over. A song repeated at once is the same track, unless the
 * player gives no media id but separate queue items: the known limit the architecture names.
 * The pipeline's lookup key is separate and unaffected.
 */
object TrackIdentity {
    fun same(
        a: NowPlaying,
        b: NowPlaying,
    ): Boolean =
        when {
            a.player != b.player -> false
            a.mediaId != null && b.mediaId != null -> a.mediaId == b.mediaId
            a.queueItemId != null && b.queueItemId != null -> a.queueItemId == b.queueItemId
            else -> a.title == b.title && (a.artists == b.artists || a.artists.isEmpty() || b.artists.isEmpty())
        }
}
