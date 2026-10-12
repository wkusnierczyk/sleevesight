package io.github.wkusnierczyk.sleevesight.spike

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackChangeTest {
    private val playing = 3
    private val paused = 2

    @Test
    fun `a playing state is extrapolated by elapsed time and speed`() {
        val state = TrackChange.State(playing, position = 10_000, updateTime = 1_000, speed = 1f, receivedAt = 1_000)
        assertEquals(15_000, TrackChange.positionAt(state, now = 6_000, playing = playing))
    }

    @Test
    fun `a paused state keeps its position`() {
        val state = TrackChange.State(paused, position = 10_000, updateTime = 1_000, speed = 0f, receivedAt = 1_000)
        assertEquals(10_000, TrackChange.positionAt(state, now = 60_000, playing = playing))
    }

    @Test
    fun `remaining time is the duration less the position, unknown without a duration`() {
        assertEquals(12_000, TrackChange.remaining(duration = 200_000, position = 188_000))
        assertNull(TrackChange.remaining(duration = 0, position = 188_000))
    }

    @Test
    fun `only states inside the window are recent`() {
        val states =
            listOf(1_000L, 8_000L, 9_500L).map {
                TrackChange.State(playing, position = 0, updateTime = it, speed = 1f, receivedAt = it)
            }
        assertEquals(
            listOf(8_000L, 9_500L),
            TrackChange.recent(states, now = 10_000, windowMs = 3_000).map { it.receivedAt },
        )
    }

    private fun state(
        position: Long,
        at: Long,
    ) = TrackChange.State(playing, position = position, updateTime = at, speed = 1f, receivedAt = at)

    @Test
    fun `with metadata first the last state is the previous track's, extrapolated to now`() {
        val estimate =
            TrackChange.estimate(
                listOf(state(170_000, at = 1_000)),
                now = 11_000,
                playing = playing,
                windowMs = 3_000,
            )!!
        assertEquals(180_000, estimate.position)
        assertNull(estimate.newTrackFrom)
    }

    @Test
    fun `with the new state first the estimate comes from the state before the reset`() {
        val states = listOf(state(170_000, at = 1_000), state(0, at = 10_000))
        val estimate = TrackChange.estimate(states, now = 10_200, playing = playing, windowMs = 3_000)!!
        assertEquals(179_000, estimate.position)
        assertEquals(10_000, estimate.changeAt)
        assertEquals(1, estimate.newTrackFrom)
    }

    @Test
    fun `a seek backwards before the window is not taken for the change`() {
        val states = listOf(state(50_000, at = 1_000), state(10_000, at = 2_000))
        val estimate = TrackChange.estimate(states, now = 100_000, playing = playing, windowMs = 3_000)!!
        assertEquals(108_000, estimate.position)
        assertNull(estimate.newTrackFrom)
    }

    @Test
    fun `a re-published state with the same position and update time is not a reset`() {
        val first = state(170_000, at = 1_000)
        val again = first.copy(receivedAt = 2_500)
        val estimate = TrackChange.estimate(listOf(first, again), now = 3_000, playing = playing, windowMs = 3_000)!!
        assertNull(estimate.newTrackFrom)
        assertEquals(172_000, estimate.position)
    }
}
