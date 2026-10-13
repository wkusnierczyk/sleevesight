package io.github.wkusnierczyk.sleevesight.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RetryAfterTest {
    /** Sun, 06 Nov 1994 08:49:37 GMT, the RFC's example. */
    private val rfcExample = 784_111_777_000L
    private val justBefore = Instant.fromEpochMilliseconds(rfcExample - 5_000)

    @Test
    fun `delay-seconds are digits only, and too many to count end the retries`() {
        assertEquals(120.seconds, parseRetryAfter(" 120 ", justBefore))
        assertEquals(Duration.ZERO, parseRetryAfter("00", justBefore))
        assertEquals(Duration.INFINITE, parseRetryAfter("99999999999999999999999", justBefore))
        assertNull(parseRetryAfter("+5", justBefore))
        assertNull(parseRetryAfter("-5", justBefore))
        assertNull(parseRetryAfter("soon", justBefore))
        assertNull(parseRetryAfter("", justBefore))
    }

    @Test
    fun `the three HTTP-date forms are read`() {
        for (date in listOf(
            "Sun, 06 Nov 1994 08:49:37 GMT",
            "Sunday, 06-Nov-94 08:49:37 GMT",
            "Sun Nov  6 08:49:37 1994",
        )) {
            assertEquals(5.seconds, parseRetryAfter(date, justBefore), date)
        }
    }

    @Test
    fun `a date in the past is no wait`() {
        assertEquals(
            Duration.ZERO,
            parseRetryAfter(
                "Sun, 06 Nov 1994 08:49:37 GMT",
                Instant.fromEpochMilliseconds(
                    rfcExample + 60_000,
                ),
            ),
        )
    }

    @Test
    fun `an RFC 850 year more than 50 years ahead is read in the past century`() {
        val now = Instant.fromEpochMilliseconds(1_790_000_000_000L) // 2026
        assertEquals(1994, yearOf(parseHttpDate("Sunday, 06-Nov-94 08:49:37 GMT", now)))
        assertEquals(2030, yearOf(parseHttpDate("Wednesday, 06-Nov-30 08:49:37 GMT", now)))
        assertEquals(2076, yearOf(parseHttpDate("Friday, 06-Nov-76 08:49:37 GMT", now)))
        assertEquals(1977, yearOf(parseHttpDate("Sunday, 06-Nov-77 08:49:37 GMT", now)))
    }

    @Test
    fun `a malformed or impossible date is null`() {
        assertNull(parseHttpDate("Sun, 06 Foo 1994 08:49:37 GMT", justBefore))
        assertNull(parseHttpDate("Sun, 06 Nov 1994 25:49:37 GMT", justBefore))
        assertNull(parseHttpDate("Sun, 6 Nov 1994 08:49:37 GMT", justBefore))
    }

    /** The year of [millis], by the IMF-fixdate of 1 January in nearby years. */
    private fun yearOf(millis: Long?): Int? =
        (1950..2100).lastOrNull { year ->
            millis != null && millis >= checkNotNull(parseHttpDate("Thu, 01 Jan $year 00:00:00 GMT", justBefore))
        }
}
