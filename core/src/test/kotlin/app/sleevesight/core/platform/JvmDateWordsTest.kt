package app.sleevesight.core.platform

import app.sleevesight.core.text.DateWords
import app.sleevesight.core.text.EnglishDateWords
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Dates in a language's own form through `java.time` (#957), from the patterns a platform gives for
 * the skeletons, here as Android and CLDR give them; with the English ones, the app's portable form.
 */
class JvmDateWordsTest {
    // Each language's patterns for the day, the month and the year.
    private val patterns =
        mapOf(
            "en" to listOf("d MMMM y", "MMMM y", "y"),
            "de" to listOf("d. MMMM y", "MMMM y", "y"),
            "pl" to listOf("d MMMM y", "LLLL y", "y"),
            "ja" to listOf("y年M月d日", "y年M月", "y年"),
            "eu" to listOf("y('e')'ko' MMMM'ren' d('a')", "y('e')'ko' MMMM", "y"),
        )

    private val words =
        JvmDateWords { skeleton, locale ->
            val (day, month, year) = patterns.getValue(locale.language)
            when (skeleton) {
                JvmDateWords.DAY -> day
                JvmDateWords.MONTH -> month
                else -> year
            }
        }

    @Test
    fun `with the English patterns it writes what the portable form writes, at every precision`() {
        val dates =
            listOf(
                "2014-07-29",
                "2014-07-09",
                "2014-07",
                "2014",
                "1974-03-00",
                "1974-00-00",
                // A year before 1000, its four digits as the sources give them.
                "0965-03-25",
                "0965-03",
                "0965",
                // A day the month does not have, as given.
                "1974-02-30",
                "2014-02-29",
            )
        for (language in listOf(DateWords.TEXT_LANGUAGE, "en-US", "en", "en-IN")) {
            for (date in dates) {
                assertEquals(
                    EnglishDateWords.format(date, language),
                    words.format(date, language),
                    "$date in $language",
                )
            }
        }
        assertEquals("25 March 0965", words.format("0965-03-25", DateWords.TEXT_LANGUAGE))
        assertEquals("30 February 1974", words.format("1974-02-30", DateWords.TEXT_LANGUAGE))
    }

    @Test
    fun `in German the language's own form`() {
        assertEquals("29. Juli 2014", words.format("2014-07-29", "de"))
        assertEquals("Juli 2014", words.format("2014-07", "de"))
        assertEquals("2014", words.format("2014", "de"))
        assertEquals("März 1974", words.format("1974-03-00", "de"))
        assertEquals(listOf("17. Oktober 2026", "Oktober 2026", "2026"), words.examples("de"))
    }

    @Test
    fun `in Polish the month declined with a day, standalone without one`() {
        assertEquals("29 lipca 2014", words.format("2014-07-29", "pl"))
        assertEquals("lipiec 2014", words.format("2014-07", "pl"))
        assertEquals("2014", words.format("2014", "pl"))
        assertEquals("marzec 1974", words.format("1974-03-00", "pl"))
    }

    @Test
    fun `in Japanese the year and the month with their own marks`() {
        assertEquals("2014年7月29日", words.format("2014-07-29", "ja"))
        assertEquals("2014年7月", words.format("2014-07", "ja"))
        assertEquals("2014年", words.format("2014", "ja"))
    }

    @Test
    fun `in Basque each precision with its own endings, none left over from another`() {
        assertEquals("2014(e)ko uztailaren 29(a)", words.format("2014-07-29", "eu"))
        assertEquals("2014(e)ko uztaila", words.format("2014-07", "eu"))
        assertEquals("2014", words.format("2014", "eu"))
    }
}
