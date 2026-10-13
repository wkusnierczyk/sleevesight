package io.github.wkusnierczyk.sleevesight.net

import kotlin.time.Instant

/**
 * An HTTP-date (RFC 9110, 5.6.7) as epoch milliseconds, in any of the three forms a recipient must
 * accept: IMF-fixdate (`Sun, 06 Nov 1994 08:49:37 GMT`), the obsolete RFC 850
 * (`Sunday, 06-Nov-94 08:49:37 GMT`, its two-digit year read as the RFC says, against [now]) and
 * asctime (`Sun Nov  6 08:49:37 1994`). Null if [text] is none of them or not a real time (31
 * February, 29 February outside a leap year, 25:00).
 */
internal fun parseHttpDate(
    text: String,
    now: Instant,
): Long? {
    val match = IMF_FIXDATE.matchEntire(text) ?: RFC_850.matchEntire(text) ?: ASCTIME.matchEntire(text) ?: return null

    fun part(name: String) = checkNotNull(match.groups[name]).value
    val month = MONTHS.indexOf(part("month").lowercase()) + 1
    val day = part("day").trim().toInt()
    val (hour, minute, second) = listOf(part("hour"), part("minute"), part("second")).map { it.toInt() }
    val validTime = month >= 1 && hour < HOURS_PER_DAY && minute < MINUTES_PER_HOUR && second <= LEAP_SECOND
    val time = ((hour * MINUTES_PER_HOUR + minute) * SECONDS_PER_MINUTE + second) * MILLIS_PER_SECOND
    val yearText = part("year")
    val year = if (yearText.length == 2) fullYear(yearText.toInt(), month, day, time, now) else yearText.toInt()
    return if (validTime && day in 1..daysIn(year, month)) epochDays(year, month, day) * MILLIS_PER_DAY + time else null
}

private const val TIME = "(?<hour>\\d{2}):(?<minute>\\d{2}):(?<second>\\d{2})"
private val IMF_FIXDATE = Regex("[A-Za-z]{3}, (?<day>\\d{2}) (?<month>[A-Za-z]{3}) (?<year>\\d{4}) $TIME GMT")
private val RFC_850 = Regex("[A-Za-z]+, (?<day>\\d{2})-(?<month>[A-Za-z]{3})-(?<year>\\d{2}) $TIME GMT")
private val ASCTIME = Regex("[A-Za-z]{3} (?<month>[A-Za-z]{3}) (?<day>[ \\d]\\d) $TIME (?<year>\\d{4})")
private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
private const val MILLIS_PER_SECOND = 1_000L
private const val MILLIS_PER_DAY = 86_400_000L
private const val LEAP_SECOND = 60
private const val CENTURY = 100
private const val FUTURE_LIMIT = 50

/**
 * RFC 9110: a two-digit year that would put the date more than 50 years after [now] is the most
 * recent past year with the same last two digits. Compared on the full timestamp, not the year.
 */
private fun fullYear(
    twoDigits: Int,
    month: Int,
    day: Int,
    time: Long,
    now: Instant,
): Int {
    val nowMillis = now.toEpochMilliseconds()
    val today = nowMillis.floorDiv(MILLIS_PER_DAY)
    val (thisYear, thisMonth, thisDay) = civil(today)
    val limit = epochDays(thisYear + FUTURE_LIMIT, thisMonth, thisDay) * MILLIS_PER_DAY + nowMillis.mod(MILLIS_PER_DAY)

    // The day clamped to the month, so the comparison holds for an impossible date too; it is refused after.
    fun at(year: Int) =
        epochDays(year, month.coerceIn(1, MONTHS.size), day.coerceIn(1, daysIn(year, month))) * MILLIS_PER_DAY + time
    val candidate = thisYear / CENTURY * CENTURY + twoDigits
    return when {
        at(candidate) > limit -> candidate - CENTURY
        at(candidate + CENTURY) <= limit -> candidate + CENTURY
        else -> candidate
    }
}

@Suppress("MagicNumber") // The calendar's own numbers.
private fun daysIn(
    year: Int,
    month: Int,
): Int =
    when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

// Howard Hinnant's days_from_civil and civil_from_days, for the proleptic Gregorian calendar.
@Suppress("MagicNumber") // The algorithm's own constants.
private fun epochDays(
    year: Int,
    month: Int,
    day: Int,
): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = y.floorDiv(400L)
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

/** Year, month and day of [epochDays]. */
@Suppress("MagicNumber") // The algorithm's own constants.
private fun civil(epochDays: Long): Triple<Int, Int, Int> {
    val z = epochDays + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return Triple((yoe + era * 400 + if (month <= 2) 1 else 0).toInt(), month, day)
}
