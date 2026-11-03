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
 * @property year the year of the release, when the player sends one (`YEAR`, else `DATE`): the
 *   header's year after the album when MusicBrainz has none for it (#556). Few players send it.
 * @property play which play of a track this report belongs to, as the app's session layer counts
 *   them (`design/architecture.md`, *Identity*): equal across every report of one play, so a track
 *   filled in as it plays (its artists, its album) stays one; null where nothing counted it (the
 *   CLI, a queue entry, a row read back from the history).
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
    val year: Int? = null,
    val play: Long? = null,
)
