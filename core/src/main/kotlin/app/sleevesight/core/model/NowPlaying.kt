package app.sleevesight.core.model

import kotlin.time.Duration

/**
 * What a player's media session reports about the current track, as the session layer maps it
 * (`design/architecture.md`, *Session layer*), before normalization.
 *
 * The art bitmap is a platform type, so it stays with the session layer in `:app`; only an art
 * URI, which some players send instead, is carried here.
 *
 * @property title the title as the player reports it, version suffixes and all.
 * @property artists the player's artist string, possibly several names separated by commas.
 * @property player the player's package name.
 * @property mediaId the player's media id, when it sends one.
 * @property queueItemId the active queue item, when the player publishes one; the session's −1
 *   ("no item") is null here.
 */
data class NowPlaying(
    val title: String,
    val artists: String,
    val album: String? = null,
    val duration: Duration? = null,
    val player: String,
    val mediaId: String? = null,
    val queueItemId: Long? = null,
    val artUri: String? = null,
)
