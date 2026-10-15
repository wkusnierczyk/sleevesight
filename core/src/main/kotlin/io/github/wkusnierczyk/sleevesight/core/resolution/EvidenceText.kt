package io.github.wkusnierczyk.sleevesight.core.resolution

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
     * A date as prose, at the precision it has: `1974` is `in 1974`, `1974-01` is `in January
     * 1974`, `1974-03-25` is `on 25 March 1974`. A month or day of `00` counts as absent, as
     * Wikidata writes an unknown one.
     */
    fun date(date: String): String {
        val parts = date.split('-')
        val month = parts.getOrNull(1)?.toIntOrNull()?.let { MONTHS.getOrNull(it - 1) }
        val day = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 }
        return when {
            month == null -> "in ${parts[0]}"
            day == null -> "in $month ${parts[0]}"
            else -> "on $day $month ${parts[0]}"
        }
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
