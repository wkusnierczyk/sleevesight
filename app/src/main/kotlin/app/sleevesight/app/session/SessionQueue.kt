package app.sleevesight.app.session

/**
 * The queue a player publishes with `getQueue()` and `getQueueTitle()` (`design/architecture.md`,
 * *Session layer*). Spotify's starts with the playing track and the tracks after it, losing one
 * item per track played, and is titled "Play Queue"; YouTube Music's "Up next" starts with the
 * playing track too (Phase 0 probes).
 */
data class SessionQueue(
    val title: String?,
    val items: List<QueueEntry>,
)

/**
 * One queue item, as its description gives it.
 *
 * @property id the player's queue item id, which the playback state names as the active item.
 * @property subtitle usually the artists.
 */
data class QueueEntry(
    val id: Long,
    val title: String?,
    val subtitle: String?,
    val mediaId: String?,
)
