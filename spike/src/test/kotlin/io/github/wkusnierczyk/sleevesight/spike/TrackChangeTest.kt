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
}
