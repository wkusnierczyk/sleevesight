package app.sleevesight.core.platform

import app.sleevesight.core.text.DateWhen
import app.sleevesight.core.text.DateWords
import java.time.chrono.IsoEra
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.temporal.ChronoField
import java.time.temporal.TemporalAccessor
import java.time.temporal.TemporalField
import java.time.temporal.UnsupportedTemporalTypeException
import java.util.Locale

/**
 * [DateWords] on the JVM and Android, through `java.time` (#957): a date in its language's own
 * pattern for the skeleton [DAY], [MONTH] or [YEAR], from [pattern], the platform's: on the phone,
 * `DateFormat.getBestDateTimePattern`, which the app passes. The plain JVM has no patterns by skeleton
 * (`:core` targets Java 17), and a pattern made by trimming a long date's would leave its grammar
 * behind (Basque "2014(e)ko uztailaren" for July 2014), so none is made here: English elsewhere goes
 * through [EnglishDateWords][app.sleevesight.core.text.EnglishDateWords]. The month's name is the
 * pattern's own form, so a language that declines it gets the form the pattern asks for: Polish "29
 * lipca 2014" beside "lipiec 2014". The year keeps the four digits the sources give it (`0965`), and a
 * day is written as given, as `EnglishDateWords` writes them, even one the month does not have (30
 * February): with the English patterns the two agree.
 */
class JvmDateWords(
    private val pattern: (skeleton: String, locale: Locale) -> String,
) : DateWords {
    override fun format(
        date: DateWhen,
        language: String,
    ): String {
        val locale = Locale.forLanguageTag(language)
        val year = date.year.toLong()
        val fields =
            mutableMapOf<TemporalField, Long>(
                ChronoField.YEAR to year,
                ChronoField.YEAR_OF_ERA to year,
                ChronoField.ERA to IsoEra.CE.value.toLong(),
            )
        val skeleton =
            when (date) {
                is DateWhen.Day -> {
                    fields[ChronoField.MONTH_OF_YEAR] = date.month.toLong()
                    fields[ChronoField.DAY_OF_MONTH] = date.day.toLong()
                    DAY
                }
                is DateWhen.Month -> {
                    fields[ChronoField.MONTH_OF_YEAR] = date.month.toLong()
                    MONTH
                }
                is DateWhen.Year -> YEAR
            }
        return DateTimeFormatter
            .ofPattern(fourDigitYear(pattern(skeleton, locale)), locale)
            .withDecimalStyle(DecimalStyle.of(locale))
            .format(Fields(fields))
    }

    // The date's fields as they are, unchecked, so a day the month does not have is written as given.
    private class Fields(
        private val values: Map<TemporalField, Long>,
    ) : TemporalAccessor {
        override fun isSupported(field: TemporalField): Boolean = field in values

        override fun getLong(field: TemporalField): Long =
            values[field] ?: throw UnsupportedTemporalTypeException("Not a field of the date: $field")
    }

    companion object {
        /** The skeleton of a date with its day: "29 July 2014". */
        const val DAY: String = "dMMMMy"

        /** The skeleton of a month: "July 2014". */
        const val MONTH: String = "MMMMy"

        /** The skeleton of a year: "2014". */
        const val YEAR: String = "y"

        // [pattern] with a lone `y` written `yyyy`: the year with the four digits the sources give it.
        private fun fourDigitYear(pattern: String): String =
            tokens(pattern).joinToString("") { if (it == "y") "yyyy" else it }

        // A pattern's tokens: each run of one pattern letter, each quoted literal (its quotes kept,
        // `''` a quote), and each other character.
        private fun tokens(pattern: String): List<String> = TOKEN.findAll(pattern).map { it.value }.toList()

        private val TOKEN = Regex("'(?:[^']|'')*'|([A-Za-z])\\1*|.")
    }
}
