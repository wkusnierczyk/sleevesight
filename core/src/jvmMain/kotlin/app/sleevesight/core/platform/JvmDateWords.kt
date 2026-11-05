package app.sleevesight.core.platform

import app.sleevesight.core.text.DateWhen
import app.sleevesight.core.text.DateWords
import java.time.chrono.IsoChronology
import java.time.chrono.IsoEra
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.time.temporal.ChronoField
import java.time.temporal.TemporalAccessor
import java.time.temporal.TemporalField
import java.time.temporal.UnsupportedTemporalTypeException
import java.util.Locale

/**
 * [DateWords] on the JVM and Android, through `java.time` (#957): a date in its language's own
 * pattern for the skeleton [DAY], [MONTH] or [YEAR], from [pattern], which the app passes as
 * `DateFormat.getBestDateTimePattern` and is [jvmPattern] elsewhere (the tests, the CLI). The month's
 * name is the pattern's own form, so a language that declines it gets the form the pattern asks for:
 * Polish "29 lipca 2014" beside "lipiec 2014". The year keeps the four digits the sources give it
 * (`0965`), and a day is written as given, as [EnglishDateWords][app.sleevesight.core.text.EnglishDateWords]
 * writes them, even one the month does not have (30 February): in English the two agree.
 */
class JvmDateWords(
    private val pattern: (skeleton: String, locale: Locale) -> String = ::jvmPattern,
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

        /**
         * The pattern for [skeleton] in [locale] where no platform's skeleton patterns are at hand.
         * In English, the app's form whatever the variety (`d MMMM y`, `MMMM y`, `y`), as
         * [EnglishDateWords][app.sleevesight.core.text.EnglishDateWords] writes it. In another
         * language, good enough for the tests and the CLI: the day's is the locale's long date
         * pattern (German `d. MMMM y`); the month's is that less the day and the literal text after
         * it, the month's name in its standalone form (`LLLL y`); the year's is the month's less the
         * month and the literal text after it. The phone's own patterns can differ.
         */
        fun jvmPattern(
            skeleton: String,
            locale: Locale,
        ): String {
            require(skeleton == DAY || skeleton == MONTH || skeleton == YEAR) { "Not a date skeleton: $skeleton" }
            if (locale.language == Locale.ENGLISH.language) {
                return when (skeleton) {
                    DAY -> "d MMMM y"
                    MONTH -> "MMMM y"
                    else -> "y"
                }
            }
            val long =
                DateTimeFormatterBuilder.getLocalizedDateTimePattern(
                    FormatStyle.LONG,
                    null,
                    IsoChronology.INSTANCE,
                    locale,
                )
            val day = tokens(long)
            val month = without(day, 'd').map { if (it.first() == 'M') "L".repeat(it.length) else it }
            return when (skeleton) {
                DAY -> day
                MONTH -> month
                else -> without(month, 'L')
            }.joinToString("").trim()
        }

        // [pattern] with a lone `y` written `yyyy`: the year with the four digits the sources give it.
        private fun fourDigitYear(pattern: String): String =
            tokens(pattern).joinToString("") { if (it == "y") "yyyy" else it }

        // [tokens] less the first field of [letter], and the literal text after it up to the next field.
        private fun without(
            tokens: List<String>,
            letter: Char,
        ): List<String> {
            val at = tokens.indexOfFirst { isField(it) && it.first() == letter }
            if (at < 0) return tokens
            val next = tokens.drop(at + 1).indexOfFirst(::isField)
            val end = if (next < 0) tokens.size else at + 1 + next
            return tokens.subList(0, at) + tokens.subList(end, tokens.size)
        }

        private fun isField(token: String): Boolean = token.first().let { it in 'a'..'z' || it in 'A'..'Z' }

        // A pattern's tokens: each run of one pattern letter, each quoted literal (its quotes kept,
        // `''` a quote), and each other character.
        private fun tokens(pattern: String): List<String> = TOKEN.findAll(pattern).map { it.value }.toList()

        private val TOKEN = Regex("'(?:[^']|'')*'|([A-Za-z])\\1*|.")
    }
}
