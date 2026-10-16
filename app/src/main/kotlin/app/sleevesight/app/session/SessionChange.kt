package app.sleevesight.app.session

import app.sleevesight.core.model.NowPlaying

/**
 * A track change as the session layer saw it, kept for skip detection (D5; `design/design.md`,
 * *Track changes*), which #53 builds. [SessionRepository.nowPlaying] changes only once the
 * player's reports have settled, up to [SessionRepository.MAX_SETTLE] after the change began, by
 * when the new track's first state (position 0) has usually arrived; so D5 measures from
 * [changedAt] and [before], not from the emission.
 *
 * @property track the new track.
 * @property changedAt when the first report of the change arrived, on the
 *   `SystemClock.elapsedRealtime` clock: the first callback of the burst that settled into [track].
 * @property before the player's playback state just before that burst, which holds the previous
 *   track's last reported position, for the time it had left; null when the change came with no
 *   burst (the session shown switched to another player's track).
 */
data class SessionChange(
    val track: NowPlaying,
    val changedAt: Long,
    val before: PlaybackInfo?,
)
