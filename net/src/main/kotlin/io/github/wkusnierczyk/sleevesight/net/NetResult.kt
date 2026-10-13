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
 * Every way a request can fail (architecture, Networking). None carries an exception message or a
 * URL, which could hold a key.
 */
sealed interface NetError {
    /** No response: no connection, a DNS failure, a timeout, a dropped connection. [reason] names the kind. */
    data class Offline(
        val reason: String,
    ) : NetError

    /** 429, still there after the retries, or asking for a wait longer than they allow. */
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

    /** The response arrived but could not be read as the caller expected. */
    data class Parse(
        val reason: String,
    ) : NetError
}
