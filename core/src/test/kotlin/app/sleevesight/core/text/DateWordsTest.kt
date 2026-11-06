package app.sleevesight.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A date at the precision its source gives (#724), and the app's English form of it (#957). */
class DateWordsTest {
    @Test
    fun `a date is as precise as its source gives it`() {
        assertEquals(DateWhen.Day(29, 7, "2014"), DateWhen.of("2014-07-29"))
        assertEquals(DateWhen.Day(3, 12, "1965"), DateWhen.of("1965-12-03"))
        assertEquals(DateWhen.Month(7, "2014"), DateWhen.of("2014-07"))
        assertEquals(DateWhen.Year("2014"), DateWhen.of("2014"))
        // A part that is not a month or a day leaves it less precise: Wikidata's `00`, a month past
        // December, a day past the 31st. A day the month does not have stays, as the sources give it.
        assertEquals(DateWhen.Year("1974"), DateWhen.of("1974-00-00"))
        assertEquals(DateWhen.Year("1974"), DateWhen.of("1974-00-25"))
        assertEquals(DateWhen.Month(3, "1974"), DateWhen.of("1974-03-00"))
        assertEquals(DateWhen.Year("2014"), DateWhen.of("2014-13-01"))
        assertEquals(DateWhen.Month(7, "2014"), DateWhen.of("2014-07-32"))
        assertEquals(DateWhen.Day(30, 2, "1974"), DateWhen.of("1974-02-30"))
        // The year as given, its four digits.
        assertEquals(DateWhen.Year("0965"), DateWhen.of("0965"))
        // No year, no date.
        assertNull(DateWhen.of("19"))
        assertNull(DateWhen.of("19745"))
        assertNull(DateWhen.of("0000"))
        assertNull(DateWhen.of("abcd-07"))
        assertNull(DateWhen.of(""))
        assertFailsWith<IllegalArgumentException> { DateWhen.Day(32, 7, "2014") }
        assertFailsWith<IllegalArgumentException> { DateWhen.Month(0, "2014") }
    }

    @Test
    fun `the app's English form, whatever the language asked for`() {
        for (language in listOf(DateWords.APP_LANGUAGE, "en-US", "de")) {
            assertEquals("29 July 2014", EnglishDateWords.format("2014-07-29", language))
            assertEquals("9 July 2014", EnglishDateWords.format("2014-07-09", language))
            assertEquals("July 2014", EnglishDateWords.format("2014-07", language))
            assertEquals("2014", EnglishDateWords.format("2014", language))
        }
        assertEquals("25 March 0965", EnglishDateWords.format("0965-03-25", DateWords.APP_LANGUAGE))
        assertNull(EnglishDateWords.format("19", DateWords.APP_LANGUAGE))
    }

    @Test
    fun `the examples are of 17 October 2026, at each precision`() {
        assertEquals(
            listOf("17 October 2026", "October 2026", "2026"),
            EnglishDateWords.examples(DateWords.APP_LANGUAGE),
        )
    }

    @Test
    fun `a form writes its dates and examples in its language, the app's where no phone gives one`() {
        assertEquals(listOf("17 October 2026", "October 2026", "2026"), DateForm.APP.examples)
        assertEquals("29 July 2014", DateForm.APP.format(DateWhen.Day(29, 7, "2014")))
        assertEquals(listOf("October 17, 2026", "October 2026", "2026"), US_DATES.examples)
        assertEquals("July 29, 2014", US_DATES.format(DateWhen.Day(29, 7, "2014")))
    }
}
