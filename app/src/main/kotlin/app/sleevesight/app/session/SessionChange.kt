package app.sleevesight.app.session

import app.sleevesight.core.model.NowPlaying

/**
 * A track change as the session layer saw it, kept for skip detection (D5; `design/design.md`,
 * *Track changes*), which #53 builds. [SessionRepository.nowPlaying] changes only once the
 * player's reports have settled: up to [SessionRepository.MAX_SETTLE] after the change began,
 * unless the title or the queue item changes again in the last [SessionRepository.SETTLE] of
 * that, and up to [SessionRepository.LONGEST_BURST] after the burst's first report in any case.
 * By then the new track's first state (position 0) has usually arrived; so D5 measures from
 * [changedAt] and [before], not from the emission.
 *
 * @property track the new track.
 * @property changedAt when the first report of the change arrived, on the
 *   `SystemClock.elapsedRealtime` clock: the first callback of the burst that settled into [track],
 *   or within it the first that named a new title or queue item, the reports before it having
 *   moved only the position; or a state that moved the position back up to
 *   [SessionRepository.SETTLE] before that report, a new track's first state naming no new item
 *   (Castbox). On a switch to another player's track settled earlier (the player shown paused),
 *   and for any change that began before the last one published, the time it was published:
 *   [SessionRepository.lastChange]'s times never go back.
 * @property before the player's playback state just before that report or state, which holds the
 *   previous track's last reported position, for the time it had left; null on a switch to
 *   another player's track, or when [changedAt] is the time of publishing.
 */
data class SessionChange(
    val track: NowPlaying,
    val changedAt: Long,
    val before: PlaybackInfo?,
)
