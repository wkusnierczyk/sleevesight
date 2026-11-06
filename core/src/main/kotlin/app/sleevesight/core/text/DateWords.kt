package app.sleevesight.core.text

/**
 * A date as precisely as a source gives it (#724, #957): a [Day], a [Month] or a [Year], read from
 * MusicBrainz's partial dates (`2014`, `2014-07`, `2014-07-29`) and Wikidata's as `WikidataEvidence`
 * reads them. A month or a day that is not one (`00`, as Wikidata writes an unknown one, a month past
 * December, a day past the 31st) leaves the date less precise; a year that is not four digits, or is
 * `0000`, leaves no date. The year is kept as given, its four digits, and a day the month does not
 * have (30 February) as given, as the app and the evidence lines always wrote them.
 */
sealed interface DateWhen {
    val year: String

    data class Day(
        val day: Int,
        val month: Int,
        override val year: String,
    ) : DateWhen {
        init {
            require(month in 1..MONTHS && day in 1..DAYS) { "Not a day: $year-$month-$day" }
        }
    }

    data class Month(
        val month: Int,
        override val year: String,
    ) : DateWhen {
        init {
            require(month in 1..MONTHS) { "Not a month: $year-$month" }
        }
    }

    data class Year(
        override val year: String,
    ) : DateWhen

    companion object {
        /** [date] (`2014`, `2014-07`, `2014-07-29`), or null when it has no year. */
        fun of(date: String): DateWhen? {
            val parts = date.split('-')
            val year = parts[0]
            if (year.length != YEAR_LENGTH || year.any { it !in '0'..'9' } || year.toInt() == 0) return null
            val month = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..MONTHS }
            val day = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..DAYS }
            return when {
                month == null -> Year(year)
                day == null -> Month(month, year)
                else -> Day(day, month, year)
            }
        }

        private const val YEAR_LENGTH = 4
        private const val MONTHS = 12
        private const val DAYS = 31
    }
}

/**
 * Dates in words (#957): *Dates*' sentences, the evidence lines the model reads (`EvidenceText.date`),
 * and the form the prompts ask the model to write dates in (`InsightPrompt.dateRule`), each date at
 * the precision it has ([DateWhen]), in a language given by its BCP 47 tag. Portable, as `:core`'s
 * code is (D2): [EnglishDateWords] writes the app's own form with no platform's help, for the
 * evidence lines and wherever no phone gives a form (the CLI); `JvmDateWords` writes any language
 * with the platform's patterns, on the phone those the app passes from
 * `DateFormat.getBestDateTimePattern`, in the form the phone's settings give ([DateForm]). With the
 * app's English patterns the two agree.
 */
fun interface DateWords {
    /** [date] in [language]'s form: "29 July 2014", "July 2014", "2014" in [APP_LANGUAGE]. */
    fun format(
        date: DateWhen,
        language: String,
    ): String

    /** [date] as a source gives it ([DateWhen.of]) in [language]'s form, or null when it has no year. */
    fun format(
        date: String,
        language: String,
    ): String? = DateWhen.of(date)?.let { format(it, language) }

    /**
     * The date [EXAMPLE] in [language]'s form, at each precision, the day first: "17 October 2026",
     * "October 2026", "2026" in the app's form, the examples the prompts give (`InsightPrompt.dateRule`).
     */
    fun examples(language: String): List<String> =
        listOf(
            format(EXAMPLE, language),
            format(DateWhen.Month(EXAMPLE.month, EXAMPLE.year), language),
            format(DateWhen.Year(EXAMPLE.year), language),
        )

    companion object {
        /**
         * The language of the app's own form of a date: British English, "17 October 2026", the form
         * *Dates* had before it followed the phone. The evidence lines are always in it, whatever the
         * phone (`EvidenceText.date`), and so are the dates where no phone gives a form ([DateForm.APP]).
         */
        const val APP_LANGUAGE: String = "en-GB"

        /** The date the prompts' examples are of ([examples]). */
        val EXAMPLE: DateWhen.Day = DateWhen.Day(day = 17, month = 10, year = "2026")
    }
}

/**
 * The form the app writes dates in (#957): [words] in [language], on the phone the phone's own
 * patterns in English as the phone's language and region give it (the app's `dateLocale`):
 * "October 17, 2026" on a phone set to US English, "17 October 2026" on a British one. *Dates*'
 * sentences and the examples the prompts give (`InsightPrompt.dateRule`) are in it; the evidence
 * lines are not, so no card's key depends on the phone. While the text is English only the language
 * is English; #290 brings the text's.
 */
class DateForm(
    val words: DateWords,
    val language: String,
) {
    /** [date] in this form ([DateWords.format]). */
    fun format(date: DateWhen): String = words.format(date, language)

    /** The prompts' examples in this form ([DateWords.examples]): "17 October 2026", "October 2026", "2026". */
    val examples: List<String> get() = words.examples(language)

    companion object {
        /** The app's own form ([EnglishDateWords] in [DateWords.APP_LANGUAGE]), where no phone gives one. */
        val APP: DateForm = DateForm(EnglishDateWords, DateWords.APP_LANGUAGE)
    }
}

/**
 * The app's English form of a date ([DateWords]), with no platform's help: `25 March 1974`, `March
 * 1974`, `1974`, the day without a leading zero and the year as the source gives it. In English
 * whatever [DateWords.format]'s language; the evidence lines and the prompts' examples are English
 * until #290.
 */
object EnglishDateWords : DateWords {
    override fun format(
        date: DateWhen,
        language: String,
    ): String =
        when (date) {
            is DateWhen.Day -> "${date.day} ${MONTHS[date.month - 1]} ${date.year}"
            is DateWhen.Month -> "${MONTHS[date.month - 1]} ${date.year}"
            is DateWhen.Year -> date.year
        }

    private val MONTHS =
        listOf(
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December",
        )
}
