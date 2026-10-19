package app.sleevesight.app.session

import app.sleevesight.core.model.NowPlaying

/**
 * The next item in the player's queue, for preparing it ahead (D13; `design/architecture.md`,
 * *Preparing the next track*). Spotify's queue and YouTube Music's "Up next" start with the playing
 * track (Phase 0, `.dev` probes), and Castbox's holds its episodes, so the next item is the one
 * after the playing track's: found by its media id where the player sends one (Spotify, Castbox;
 * its active queue item can lag behind a new queue for a moment), else by its active queue item
 * (YouTube Music). A media id queued more than once is told apart by the active queue item; when
 * that item names another entry (it lags behind, still on the track before), the playing track is
 * the first of them after it. When there is no active item, it is not in the queue, or none of
 * them comes after it, the position is ambiguous. A track not found in the queue, found
 * ambiguously, or the last in it, has no next item: nothing is prepared rather than a guess. The
 * active queue item decides only where the playing track is in the queue, never whether it is the
 * same track (`TrackIdentity`).
 */
object NextInQueue {
    /**
     * The item after [playing] in [queue], as the track it would be: its title and artists (the
     * item's subtitle), the player's package, and the item's media id and queue item id; no album
     * and no length, which [QueueEntry] does not read (Spotify's items carry neither; Castbox's
     * carry both in their extras, #172). Null when there is none, or it has no title.
     */
    fun of(
        playing: NowPlaying?,
        queue: SessionQueue?,
    ): NowPlaying? {
        val items = queue?.items.orEmpty()
        val at = playing?.let { position(it, items) } ?: -1
        val next = items.getOrNull(at + 1)?.takeIf { at >= 0 && !it.title.isNullOrBlank() }
        return if (playing == null || next?.title == null) {
            null
        } else {
            NowPlaying(
                title = next.title,
                artists = next.subtitle.orEmpty(),
                player = playing.player,
                mediaId = next.mediaId,
                queueItemId = next.id,
            )
        }
    }

    // The playing track's index in [items]: by its media id, the active queue item choosing among
    // several of it, or, when it names another entry (lagging behind), the first of them after
    // it; else by its active queue item; −1 if neither, or ambiguous.
    private fun position(
        playing: NowPlaying,
        items: List<QueueEntry>,
    ): Int {
        val active = playing.queueItemId?.let { id -> items.indexOfFirst { it.id == id } } ?: -1
        val byMedia = playing.mediaId?.let { id -> items.indices.filter { items[it].mediaId == id } }.orEmpty()
        return when {
            byMedia.size == 1 -> byMedia.single()
            byMedia.size > 1 -> active.takeIf { it in byMedia } ?: byMedia.firstOrNull { active in 0..<it } ?: -1
            else -> active
        }
    }
}
