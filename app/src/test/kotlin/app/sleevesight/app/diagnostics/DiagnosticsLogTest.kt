package app.sleevesight.app.diagnostics

import app.sleevesight.core.pipeline.PipelineEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DiagnosticsLogTest {
    /** A clock that moves a second at each reading. */
    private class Ticking : Clock {
        private var now = Instant.fromEpochSeconds(1_000)

        override fun now(): Instant = now.also { now += 1.seconds }
    }

    private fun event(n: Int) = PipelineEvent.Superseded("track $n")

    @Test
    fun keepsTheEventsInOrderWithWhenEachArrived() {
        val log = DiagnosticsLog(capacity = 10, clock = Ticking())
        log.record(event(1))
        log.record(event(2))

        assertEquals(
            listOf(
                LoggedEvent(Instant.fromEpochSeconds(1_000), event(1)),
                LoggedEvent(Instant.fromEpochSeconds(1_001), event(2)),
            ),
            log.entries.value,
        )
    }

    @Test
    fun dropsTheOldestPastItsCapacity() {
        val log = DiagnosticsLog(capacity = 3, clock = Ticking())
        (1..5).forEach { log.record(event(it)) }

        assertEquals(listOf(event(3), event(4), event(5)), log.entries.value.map { it.event })
    }

    @Test
    fun clearingDropsEveryEventAndKeepsTheNext() {
        val log = DiagnosticsLog(capacity = 10, clock = Ticking())
        log.record(event(1))
        log.record(event(2))
        log.clear()
        assertEquals(emptyList<LoggedEvent>(), log.entries.value)
        log.record(event(3))
        assertEquals(listOf(event(3)), log.entries.value.map { it.event })
    }

    @Test
    fun followsAStreamOfEvents() {
        val log = DiagnosticsLog(capacity = 10, clock = Ticking())
        runBlocking { log.follow(flowOf(event(1), event(2), event(3))) }

        assertEquals(listOf(event(1), event(2), event(3)), log.entries.value.map { it.event })
    }

    @Test
    fun keepsAFewHundredByDefault() {
        val log = DiagnosticsLog(clock = Ticking())
        (1..DiagnosticsLog.CAPACITY + 1).forEach { log.record(event(it)) }

        assertEquals(DiagnosticsLog.CAPACITY, log.entries.value.size)
        assertEquals(
            event(2),
            log.entries.value
                .first()
                .event,
        )
    }
}
