package io.github.wkusnierczyk.sleevesight.net

import kotlin.time.Duration

/** The outcome of a request: a value, or a typed error that the adapters map to their outcomes. */
sealed interface NetResult<out T> {
    data class Success<out T>(
        val value: T,
    ) : NetResult<T>

    data class Failure(
        val error: NetError,
    ) : NetResult<Nothing>
}

/**
 * Every way a request can fail (architecture, Networking). None carries an exception message, a URL
 * or a header value, any of which could hold a key: only kinds, statuses, durations and field names.
 */
sealed interface NetError {
    /** No response: no connection, a DNS failure, a timeout, a dropped connection. [reason] is the exception's type. */
    data class Offline(
        val reason: String,
    ) : NetError

    /**
     * 429, or 503 from a host with a rate limit (MusicBrainz signals its limit with 503), still there
     * after the retries, or asking for a wait longer than they allow.
     */
    data class RateLimited(
        val retryAfter: Duration?,
    ) : NetError

    /** 401 or 403: a missing, wrong or revoked key. Never retried. */
    data class Unauthorized(
        val status: Int,
    ) : NetError

    /** A 5xx, still there after the retries. */
    data class Server(
        val status: Int,
    ) : NetError

    /** The response arrived but could not be read as the caller expected. [reason] is the exception's type. */
    data class Parse(
        val reason: String,
    ) : NetError

    /**
     * The request could not be sent as given: a malformed URL, a header value with control characters
     * (a key read from a file with its newline), a malformed media type. [field] names the part, never
     * its value. Never retried.
     */
    data class InvalidRequest(
        val field: String,
    ) : NetError
}
