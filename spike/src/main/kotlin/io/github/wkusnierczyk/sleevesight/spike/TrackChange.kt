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

    /** The previous track's position at the change, and what it was computed from. */
    data class Estimate(
        val basis: State,
        val changeAt: Long,
        val position: Long,
        val newTrackFrom: Int?,
    )

    /**
     * Estimates where the previous track was when the next one started.
     *
     * Players report the new track's playback state either after its metadata or before it. In
     * the second case the last state already belongs to the new track, with its position reset
     * near zero. So the estimate is taken from the last state before such a reset, within
     * [windowMs] before [now] (an earlier seek backwards is not a track change), at the time the
     * reset arrived; with no reset, from the last state, at [now]. [Estimate.newTrackFrom] is the
     * index of the first state belonging to the new track, if any.
     */
    fun estimate(
        states: List<State>,
        now: Long,
        playing: Int,
        windowMs: Long,
        toleranceMs: Long = RESET_TOLERANCE_MS,
    ): Estimate? {
        if (states.isEmpty()) return null
        val reset =
            (states.lastIndex downTo 1)
                .takeWhile { now - states[it].receivedAt <= windowMs }
                .firstOrNull { i ->
                    // Both extrapolated to the same moment, so a re-published state (same position and
                    // update time, received later) is not taken for a reset.
                    val at = states[i].receivedAt
                    positionAt(states[i], at, playing) < positionAt(states[i - 1], at, playing) - toleranceMs
                }
        val basis = if (reset != null) states[reset - 1] else states.last()
        val changeAt = if (reset != null) states[reset].receivedAt else now
        return Estimate(basis, changeAt, positionAt(basis, changeAt, playing), reset)
    }

    private const val RESET_TOLERANCE_MS = 1_000L

    /** The states received within [windowMs] before [now], oldest first. */
    fun recent(
        states: List<State>,
        now: Long,
        windowMs: Long,
    ): List<State> = states.filter { now - it.receivedAt in 0..windowMs }
}
