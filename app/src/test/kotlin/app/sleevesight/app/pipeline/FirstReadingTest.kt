package app.sleevesight.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/** The connection's first reading never overwrites a change the callback reported before it (#192). */
class FirstReadingTest {
    private val sent = mutableListOf<Boolean>()
    private val readings = FirstReading { sent += it }

    @Test
    fun `a reading with no change before it is sent, and the changes after it`() {
        readings.read(false)
        readings.changed(true)
        assertEquals(listOf(false, true), sent)
    }

    @Test
    fun `a reading taken before the network was lost is not sent after the loss`() {
        // Online when read; the loss reached the callback before the reading was sent.
        readings.changed(false)
        readings.read(true)
        readings.changed(true)
        assertEquals(listOf(false, true), sent)
    }
}
