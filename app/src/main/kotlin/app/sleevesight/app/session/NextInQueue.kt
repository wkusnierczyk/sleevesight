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
 * the first of them after it. An active item that leads instead (its state before the metadata:
 * in a queue A, B, A, C, the metadata still on the first A and the active item already on B)
 * picks the second A, so C is prepared for a moment. That cost is accepted: media-id players have
 * only been seen lagging, and a preparation makes no model call (lookups only) and is replaced by
 * the next report. When there is no active item, it is not in the queue, or none of them comes
 * after it, the position is ambiguous. A track not found in the queue, found ambiguously, or the
 * last in it, has no next item: nothing is prepared rather than a guess. Here the active queue
 * item only locates the playing track in the queue; whether a report is the same track is
 * `TrackIdentity`'s to say.
 */
object NextInQueue {
    /**
     * The item after [playing] in [queue], as the track it would be: its title and artists (the
     * item's subtitle), the player's package, and the item's media id and queue item id; no album
     * and no length, which [QueueEntry] does not read (Spotify's items carry neither; Castbox's
     * carry both in their extras, #172). An ad ([AdDetection.isAdId], #880) is never prepared:
     * the first item after the playing track that is not one is. Null when there is none, or it has
     * no title.
     */
    fun of(
        playing: NowPlaying?,
        queue: SessionQueue?,
    ): NowPlaying? {
        val items = queue?.items.orEmpty()
        val at = playing?.let { position(it, items) } ?: -1
        return playing?.takeIf { at >= 0 }?.let { firstAfter(it.player, items, at) }
    }

    /**
     * The item after [ad], the ad playing (#880), in [queue]: the first after the queue item the
     * state names for the ad that is no ad itself, as [of] gives it, so an ad break prepares the
     * track after it. Null when the ad names no queue item, it is not in the queue, or there is none.
     */
    fun afterAd(
        ad: AdPlaying?,
        queue: SessionQueue?,
    ): NowPlaying? {
        val items = queue?.items.orEmpty()
        val at = ad?.queueItemId?.let { id -> items.indexOfFirst { it.id == id } } ?: -1
        return ad?.takeIf { at >= 0 }?.let { firstAfter(it.player, items, at) }
    }

    // The first of [items] after index [at] that is no ad of [player]'s, as a track; null if it has no title.
    private fun firstAfter(
        player: String,
        items: List<QueueEntry>,
        at: Int,
    ): NowPlaying? {
        val next = items.drop(at + 1).firstOrNull { !AdDetection.isAdId(player, it.mediaId) }
        val title = next?.title?.takeIf { it.isNotBlank() } ?: return null
        return NowPlaying(
            title = title,
            artists = next.subtitle.orEmpty(),
            player = player,
            mediaId = next.mediaId,
            queueItemId = next.id,
        )
    }

    // The playing track's index in [items]: by its media id, the active queue item choosing among
    // several of it, or, when it names another entry (lagging behind), the first of them after
    // it; else by its active queue item; −1 if neither, or ambiguous. An active item that leads
    // instead (already on the next entry) picks a later match: a wrong preparation for a moment,
    // accepted (see the KDoc above).
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
