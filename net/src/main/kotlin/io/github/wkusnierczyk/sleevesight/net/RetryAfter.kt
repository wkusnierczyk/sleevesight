package io.github.wkusnierczyk.sleevesight.net

import io.ktor.http.fromHttpToGmtDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A `Retry-After` value (RFC 9110, 10.2.3) as a wait from [now]: delay-seconds, or an HTTP-date. A
 * negative value or a date in the past is no wait; a value that is neither form is null.
 */
internal fun parseRetryAfter(
    value: String?,
    now: Instant,
): Duration? {
    val text = value?.trim().orEmpty()
    val seconds = text.toLongOrNull()
    return when {
        text.isEmpty() -> null
        seconds != null -> seconds.coerceAtLeast(0).seconds
        else -> httpDate(text)?.let { (it - now.toEpochMilliseconds()).coerceAtLeast(0).milliseconds }
    }
}

/** The HTTP-date as epoch milliseconds, or null if [text] is not one. */
@Suppress("SwallowedException") // A malformed header is no Retry-After; the exception says no more.
private fun httpDate(text: String): Long? =
    try {
        text.fromHttpToGmtDate().timestamp
    } catch (e: IllegalStateException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
