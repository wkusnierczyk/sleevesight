package app.sleevesight.app.session

import android.media.session.PlaybackState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The followed player's playback state, as the session layer maps it from its `PlaybackState`
 * (`design/architecture.md`, *Session layer*). Playback never enters the pipeline: a pause, a seek
 * or a stop changes this, not `NowPlaying`. The one part of the state that `NowPlaying` carries,
 * the active queue item, is settled with the metadata first ([SessionRepository.SETTLE]), and
 * no item (YouTube Music's −1 while stopped) keeps the last one while the title is unchanged.
 * This is published at once, every state, for skip detection's count of `PLAYING` states (D5).
 *
 * @property player the player's package name; null when no followed player has a session.
 * @property position where playback was at [updatedAt], when the player reports it.
 * @property updatedAt when [position] was taken, on the `SystemClock.elapsedRealtime` clock.
 * @property speed the playback speed: 1 for normal play, 0 when paused, negative when rewinding.
 * @property actions the controls the player takes from other apps, `PlaybackState.actions`'
 *   bits (#1409, #1209): what Home's record and the player menu offer ([allows]).
 */
data class PlaybackInfo(
    val player: String?,
    val status: PlaybackStatus,
    val position: Duration? = null,
    val updatedAt: Long = 0,
    val speed: Float = 0f,
    val actions: Long = 0L,
) {
    /** Whether the player says it plays: what the record's tap and the menu's Play/Pause read. */
    val plays: Boolean get() = status == PlaybackStatus.PLAYING || status == PlaybackStatus.BUFFERING

    /** Whether the player advertises [control] ([actions]); never without a player. */
    fun allows(control: Transport): Boolean = player != null && actions and control.actions != 0L

    /** The Play or Pause that toggles the player as it is now: Pause while it [plays]. */
    val toggle: Transport get() = if (plays) Transport.PAUSE else Transport.PLAY

    /**
     * The position at [now] (`SystemClock.elapsedRealtime`), extrapolated from the last report while
     * playing, as skip detection reads the time left (`design/design.md`, *Track changes*). At the
     * reported speed, as Android defines it: speed 0 does not move, even while `PLAYING` (Media3
     * reports that during a passing loss of audio focus).
     */
    fun positionAt(now: Long): Duration? = extrapolated(now, speed)

    /**
     * The position at [now] as the home screen's bar shows it: [positionAt], except that a state that
     * says it plays at speed 0 (#595) moves at normal speed, as the player says it plays. For the bar
     * alone: skip detection reads [positionAt]. A guard, kept though the capture of Spotify Connect
     * shows no such state: its remote session's `PAUSED` at speed 1 is read as `PLAYING` before this
     * ([SessionMapping.effectiveState]), and its `PAUSED` at speed 0 stays still.
     */
    fun displayPositionAt(now: Long): Duration? = extrapolated(now, if (speed == 0f) 1f else speed)

    private fun extrapolated(
        now: Long,
        rate: Float,
    ): Duration? =
        position?.let {
            val playing = status == PlaybackStatus.PLAYING && now > updatedAt
            if (playing) it + ((now - updatedAt) * rate).toLong().milliseconds else it
        }

    companion object {
        val NONE = PlaybackInfo(player = null, status = PlaybackStatus.NONE)
    }
}

/**
 * The states of `PlaybackState` the app tells apart; the rest are [OTHER]. The session history
 * stores these names with each track change (#49), so renaming one changes what older rows mean.
 */
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

/**
 * A control Sleevesight sends a followed player (#1409, #1209) through its `MediaController`'s
 * transport controls, offered only when the player's `PlaybackState.actions` has one of [actions]:
 * Play and Pause also by the combined play/pause action, which many players advertise alone.
 */
enum class Transport(
    val actions: Long,
) {
    PLAY(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE),
    PAUSE(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE),
    PREVIOUS(PlaybackState.ACTION_SKIP_TO_PREVIOUS),
    NEXT(PlaybackState.ACTION_SKIP_TO_NEXT),
    ;

    /** Whether it moves to another track: a change it causes is the user's (D5), not detected. */
    val skips: Boolean get() = this == PREVIOUS || this == NEXT

    /**
     * Whether, to a player advertising [actions], this goes as the play/pause media button: Play or
     * Pause to one that advertises `ACTION_PLAY_PAUSE` without its own `ACTION_PLAY` or `ACTION_PAUSE`.
     */
    fun byPlayPauseButton(actions: Long): Boolean {
        val own =
            when (this) {
                PLAY -> PlaybackState.ACTION_PLAY
                PAUSE -> PlaybackState.ACTION_PAUSE
                else -> return false
            }
        return actions and own == 0L && actions and PlaybackState.ACTION_PLAY_PAUSE != 0L
    }
}
