package app.sleevesight.app.session

import app.sleevesight.core.model.NowPlaying
import kotlin.time.Duration

/**
 * A track change as the session layer saw it, kept for skip detection (D5; `design/design.md`,
 * *Track changes*), which #53 builds, and recorded with its features for learning it (D15,
 * `HistoryRecorder`). [SessionRepository.nowPlaying] changes only once the
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
 *   moved only the position; or, when that report is a new title, a state up to
 *   [SessionRepository.SETTLE] before it that fell back to the start of a track naming no new
 *   item, a new track's first state (Castbox). On a switch to another player's track settled
 *   earlier (the player shown paused), and for any change that began before the last one
 *   published, the time it was published: [SessionRepository.lastChange]'s times never go back.
 * @property before the player's playback state just before that report or state, which holds the
 *   previous track's last reported position, for the time it had left; null on a switch to
 *   another player's track, or when [changedAt] is the time of publishing.
 * @property from the track shown before, as last updated: its latest length gives [timeLeft].
 * @property states the playback states the player reported in the
 *   [SessionRepository.STATES_BEFORE_CHANGE] before that report or state, oldest first, at most
 *   [SessionRepository.MAX_STATES_BEFORE_CHANGE]: the pattern of states before the change, recorded
 *   with it for skip detection to learn from (D15). Every report is here, equal ones included,
 *   which [SessionRepository.playback], a state flow, would merge. Empty when [before] is null.
 */
data class SessionChange(
    val track: NowPlaying,
    val changedAt: Long,
    val before: PlaybackInfo?,
    val from: NowPlaying? = null,
    val states: List<StateReport> = emptyList(),
) {
    /**
     * The time [from] had left at [changedAt]: its latest length less its position then,
     * extrapolated from [before] (`design/design.md`, *Track changes*); negative when the change came
     * after the end, as Spotify's natural ends without crossfade do. Null when the length or the
     * position is unknown.
     */
    val timeLeft: Duration?
        get() {
            val length = from?.duration ?: return null
            val position = before?.positionAt(changedAt) ?: return null
            return length - position
        }
}

/**
 * A playback state as the player reported it, and when the report arrived ([at], on the
 * `SystemClock.elapsedRealtime` clock), which may differ from when its position was taken.
 */
data class StateReport(
    val at: Long,
    val state: PlaybackInfo,
)
