package app.sleevesight.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Instant

/**
 * When a kept AI answer was written, as its line says it (#851): today, its time, 24-hour or not as
 * the phone says; an earlier day, its day and month, and the year only when it is not this year; in
 * English in the form of the phone's language and region (#957), in its time zone.
 */
@RunWith(RobolectricTestRunner::class)
class KeptLineTest {
    private val zone = ZoneId.of("Europe/Oslo")
    private val now = Instant.parse("2026-11-02T15:30:00Z")

    private fun at(
        written: String,
        locale: Locale = Locale.UK,
        hours24: Boolean = true,
    ) = writtenWhen(Instant.parse(written), now, zone, locale, hours24)

    @Test
    fun `one written today says its time, as the phone's 24-hour setting says`() {
        assertEquals(Written.Today("13:04"), at("2026-11-02T12:04:00Z"))
        val twelve = at("2026-11-02T12:04:00Z", hours24 = false) as Written.Today
        assertTrue(twelve.time, twelve.time.startsWith("1:04") && "13" !in twelve.time)
    }

    @Test
    fun `one written on an earlier day says its date, with the year only when it is not this one`() {
        assertEquals(Written.OnDay("31 October"), at("2026-10-31T12:04:00Z"))
        assertEquals(Written.OnDay("31 October 2025"), at("2025-10-31T12:04:00Z"))
    }

    @Test
    fun `on a US phone the month first, on a British one the day (#957)`() {
        assertEquals(Written.OnDay("October 31"), at("2026-10-31T12:04:00Z", Locale.US))
        assertEquals(Written.OnDay("October 31, 2025"), at("2025-10-31T12:04:00Z", Locale.US))
        assertEquals(Written.OnDay("31 October"), at("2026-10-31T12:04:00Z", Locale.UK))
        assertEquals(Written.OnDay("31 October 2025"), at("2025-10-31T12:04:00Z", Locale.UK))
    }

    @Test
    fun `on a phone in another language, the time and date are in English, in the region's form`() {
        // A German phone: English as written in Germany, the day first, not the US's order (#957).
        assertEquals(Written.OnDay("31 October"), at("2026-10-31T12:04:00Z", Locale.GERMANY))
        assertEquals(Written.OnDay("31 October 2025"), at("2025-10-31T12:04:00Z", Locale.GERMANY))
        // An Egyptian phone in Arabic: no English of Egypt's, so international English.
        val arabic = Locale.forLanguageTag("ar-EG")
        assertEquals(Written.Today("13:04"), at("2026-11-02T12:04:00Z", arabic))
        assertEquals(Written.OnDay("31 October 2025"), at("2025-10-31T12:04:00Z", arabic))
    }

    @Test
    fun `today is the phone's day, in its time zone`() {
        // 22:30 UTC on the 1st is 23:30 on the 1st in Oslo: an earlier day.
        assertEquals(Written.OnDay("1 November"), at("2026-11-01T22:30:00Z"))
        // 23:30 UTC on the 1st is 00:30 on the 2nd in Oslo: today.
        assertEquals(Written.Today("00:30"), at("2026-11-01T23:30:00Z"))
    }
}
