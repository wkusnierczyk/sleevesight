package app.sleevesight.core.sources

import app.sleevesight.core.text.DateWhen

/**
 * How precisely a release's date is said (#1203). MusicBrainz's January 1 is often no day at all
 * but a year written as one, by an import that knew only the year: *A/B*'s (KALEO) first release, a
 * digital one in Iceland, is dated `2016-01-01`, and every other release of its group that year
 * `2016-06-10`. Such a date is said as its year alone. The date never moves to the later release:
 * only how precisely it is said changes.
 */
object PlaceholderDay {
    /**
     * [date], as MusicBrainz gives it, at the precision it is said at beside [others], the dates of
     * the other releases of its release group (or of its recording, for the recording's first
     * release), as MusicBrainz gives them: a January 1 (`2016-01-01`) as its year alone (`2016`)
     * when one of [others] is a later day of that year, a sign the day is a placeholder; any other
     * date as given. Only a full day counts among [others]: a year or a month alone shows nothing
     * of the day, and an undated or unreadable one counts for nothing.
     */
    fun said(
        date: String,
        others: Iterable<String?>,
    ): String {
        val year = DateWhen.of(date)?.year
        val later =
            januaryFirst(date) &&
                others.any { other ->
                    val day = other?.let(DateWhen::of) as? DateWhen.Day
                    day != null && day.year == year && (day.month != 1 || day.day != 1)
                }
        return if (later) year.orEmpty() else date
    }

    /**
     * [date], a release group's first-release date as MusicBrainz's lookup of the group gives it, as
     * precisely as [kept], the dates the track's releases of that group are kept with
     * ([ReleaseDate.collapse], which says them by [said]), say it: a January 1 as its year alone when
     * one of [kept] is that year alone, so an edition's sentence says its original's date as the
     * original's own sentence does (*A/B*'s group `2016-01-01`, its release kept as `2016`); any other
     * date as given.
     */
    fun asKept(
        date: String,
        kept: Iterable<String?>,
    ): String {
        val year = DateWhen.of(date)?.year
        return if (januaryFirst(date) && year in kept) year.orEmpty() else date
    }

    /** Whether [date], as MusicBrainz gives it, is a January 1 written to the day (`2016-01-01`). */
    fun januaryFirst(date: String): Boolean {
        val day = DateWhen.of(date) as? DateWhen.Day ?: return false
        return day.month == 1 && day.day == 1
    }
}
