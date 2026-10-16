package app.sleevesight.app.session

import android.media.session.MediaSession

/**
 * The active queue item one player's track is published with, reconciled with its metadata
 * (`design/architecture.md`, *Session layer*). The playback state names the item and the metadata
 * the track, and they arrive separately: YouTube Music and Castbox send the new track's first
 * state just before its metadata, and YouTube Music reports no item (−1) while stopped in the
 * background. Taken as they come, the item would move to the next track while the title is still
 * the last one's, and drop out and back across a stop, so a track change could be merged into
 * an update or an update read as a change. So:
 *
 * - **An item the queue names for another title** (the published item's title is present and
 *   differs from the metadata's) is not this track's.
 * - **New metadata** takes the state's item, unless it is the one the previous track had: a
 *   player that sends its metadata first still reports the previous track's item, which would
 *   make two tracks one.
 * - **The same metadata again** (a player re-sends it after a new item, as for a song queued
 *   twice) takes the state's item, when the state names one.
 * - **A new state with unchanged metadata** keeps the item already published: no item (−1) is an
 *   unknown interval, and a different item waits for the metadata that follows it. A track that
 *   had no item yet takes the state's.
 *
 * The cost: a player that moves to a new item without sending its metadata again (a repeat of
 * the same song) is not seen to change track, the known limit the architecture names.
 */
class QueueItemTracker {
    private var metadataKey: Any? = null
    private var title: String? = null
    private var item: Long? = null

    /** The item to publish after metadata for [title], keyed by [key], arrived with the state's [stateItem]. */
    fun onMetadata(
        key: Any,
        title: String?,
        stateItem: Long?,
        queue: List<MediaSession.QueueItem>?,
    ): Long? {
        val reconciled = reconcile(stateItem, title, queue)
        item =
            when {
                key != metadataKey -> reconciled?.takeIf { it != item }
                reconciled != null -> reconciled
                else -> item
            }
        metadataKey = key
        this.title = title
        return item
    }

    /** The item to publish after a new playback state or queue, the metadata unchanged. */
    fun onState(
        stateItem: Long?,
        queue: List<MediaSession.QueueItem>?,
    ): Long? {
        if (item == null) item = reconcile(stateItem, title, queue)
        return item
    }

    private fun reconcile(
        stateItem: Long?,
        title: String?,
        queue: List<MediaSession.QueueItem>?,
    ): Long? {
        val queued = stateItem?.let { id -> queue?.firstOrNull { it.queueId == id } }
        val queuedTitle =
            queued
                ?.description
                ?.title
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val other = queuedTitle != null && title != null && !queuedTitle.equals(title.trim(), ignoreCase = true)
        return stateItem.takeUnless { other }
    }
}
