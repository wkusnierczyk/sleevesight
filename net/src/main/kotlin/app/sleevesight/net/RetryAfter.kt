package app.sleevesight.net

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A `Retry-After` value (RFC 9110, 10.2.3) as a wait from [now]: delay-seconds (digits only; too many
 * to count is an infinite wait, which ends the retries), or an HTTP-date in any of its three forms. A
 * date in the past is no wait; a value that is neither form is null.
 */
internal fun parseRetryAfter(
    value: String?,
    now: Instant,
): Duration? {
    val text = value?.trim().orEmpty()
    return when {
        text.isEmpty() -> null
        DIGITS.matches(text) -> text.toLongOrNull()?.seconds ?: Duration.INFINITE
        else -> parseHttpDate(text, now)?.let { (it - now.toEpochMilliseconds()).coerceAtLeast(0).milliseconds }
    }
}

private val DIGITS = Regex("\\d+")
