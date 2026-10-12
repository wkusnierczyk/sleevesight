package io.github.wkusnierczyk.sleevesight.spike

/**
 * What the probe measures at a track change, for decision D5: how far from its end the previous
 * track was when the next one started, and which playback states came just before the change.
 *
 * Pure logic, so it is unit-tested without Android.
 */
object TrackChange {
    /** A playback state as the session reported it, with the clock reading of the report. */
    data class State(
        val state: Int,
        val position: Long,
        val updateTime: Long,
        val speed: Float,
        val receivedAt: Long,
    )

    /**
     * The previous track's playback position at [now], extrapolated from its last reported
     * [state] the way the platform does: position plus elapsed time times speed while playing.
     */
    fun positionAt(
        state: State,
        now: Long,
        playing: Int,
    ): Long =
        if (state.state == playing && state.updateTime > 0) {
            state.position + ((now - state.updateTime) * state.speed).toLong()
        } else {
            state.position
        }

    /** Milliseconds left in the previous track at the change, or null when its length is unknown. */
    fun remaining(
        duration: Long,
        position: Long,
    ): Long? = if (duration > 0) duration - position else null

    /** The states received within [windowMs] before [now], oldest first. */
    fun recent(
        states: List<State>,
        now: Long,
        windowMs: Long,
    ): List<State> = states.filter { now - it.receivedAt in 0..windowMs }
}
