package app.sleevesight.core.resolution

import app.sleevesight.core.text.DateWhen
import app.sleevesight.core.text.DateWords
import app.sleevesight.core.text.EnglishDateWords

/** Pieces of the templated evidence lines (D1) shared by the structured sources. */
object EvidenceText {
    /** `A`, `A and B`, `A, B and C`. */
    fun list(names: List<String>): String =
        when (names.size) {
            0 -> ""
            1 -> names.single()
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }

    /**
     * A date as prose, at the precision it has ([DateWhen]), in the app's English form
     * ([EnglishDateWords]), the form the prompts ask the model to write dates in (#957): `1974` is
     * `in 1974`, `1974-01` is `in January 1974`, `1974-03-25` is `on 25 March 1974`. A month or day
     * of `00` counts as absent, as Wikidata writes an unknown one. A date with no year is said as
     * given, `in` its first part. English, as the lines are, until #290.
     */
    fun date(date: String): String {
        val known = DateWhen.of(date) ?: return "in ${date.split('-')[0]}"
        val words = EnglishDateWords.format(known, DateWords.TEXT_LANGUAGE)
        return if (known is DateWhen.Day) "on $words" else "in $words"
    }
}
