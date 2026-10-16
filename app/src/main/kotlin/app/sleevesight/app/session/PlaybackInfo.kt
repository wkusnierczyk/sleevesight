package app.sleevesight.app.session

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The followed player's playback state, as the session layer maps it from its `PlaybackState`
 * (`design/architecture.md`, *Session layer*). Playback never enters the pipeline: a pause or a
 * seek changes this, never `NowPlaying`.
 *
 * @property player the player's package name; null when no followed player has a session.
 * @property position where playback was at [updatedAt], when the player reports it.
 * @property updatedAt when [position] was taken, on the `SystemClock.elapsedRealtime` clock.
 * @property speed the playback speed: 1 for normal play, 0 when paused, negative when rewinding.
 */
data class PlaybackInfo(
    val player: String?,
    val status: PlaybackStatus,
    val position: Duration? = null,
    val updatedAt: Long = 0,
    val speed: Float = 0f,
) {
    /**
     * The position at [now] (`SystemClock.elapsedRealtime`), extrapolated from the last report while
     * playing, as skip detection reads the time left (`design/design.md`, *Track changes*).
     */
    fun positionAt(now: Long): Duration? =
        position?.let {
            val playing = status == PlaybackStatus.PLAYING && now > updatedAt
            if (playing) it + ((now - updatedAt) * speed).toLong().milliseconds else it
        }

    companion object {
        val NONE = PlaybackInfo(player = null, status = PlaybackStatus.NONE)
    }
}

/** The states of `PlaybackState` the app tells apart; the rest are [OTHER]. */
enum class PlaybackStatus {
    NONE,
    STOPPED,
    PAUSED,
    PLAYING,
    BUFFERING,
    ERROR,

    /** Fast-forwarding, rewinding, skipping or connecting: a passing state. */
    OTHER,
}
