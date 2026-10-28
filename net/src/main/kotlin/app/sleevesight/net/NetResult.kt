package app.sleevesight.net

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
    /**
     * No response, or none to its end: no connection, a DNS failure, a timeout, a dropped
     * connection. [reason] is the exception's type, as `offlineReason` labels it, or a fixed label
     * of `:net`'s own ([CANCELLED_BODY]).
     */
    data class Offline(
        val reason: String,
    ) : NetError {
        /**
         * Whether it was one of the client's timeouts: to connect, between two packets, or for the
         * whole request ([Timeouts], `NetRequest.timeout`). A body that the timeout for the whole
         * request ends cleanly under the reader is [CANCELLED_BODY], which does not say (#665).
         */
        val timedOut: Boolean get() = reason in TIMEOUT_REASONS

        companion object {
            /**
             * The [reason] of a body the engine ended cleanly under the reader, as it does when the
             * call is cancelled under it: a connection lost, or the whole request's timeout.
             */
            const val CANCELLED_BODY: String = "CancelledBody"
        }
    }

    /**
     * 429, or a 503 to a GET from a host whose [HostPolicy.rateLimitedOn503] says it signals its limit
     * so (MusicBrainz), still there after the retries, or asking for a wait longer than they allow.
     * A 429 from a host whose [HostPolicy.passOn429] is set is a response instead.
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
     * The server's answer broke HTTP in a way the engine does not pass on, such as a 407 from a
     * server that is not a proxy, or could not be followed safely: a redirect from `https` to plain
     * `http` (`redirect to http refused`, #696). It answered, so it is never retried.
     */
    data class Protocol(
        val reason: String,
    ) : NetError

    /** The response body was longer than the request's `maxBytes`, [limit]. Never retried. */
    data class TooLarge(
        val limit: Long,
    ) : NetError

    /**
     * The request could not be sent as given: a malformed URL or a host not written in plain ASCII, a
     * header with control characters (a key read from a file with its newline), a malformed media
     * type, a timeout or size limit out of range. [field] is a fixed label (`url`, `header`,
     * `body content type`, `timeout`, `maxBytes`, `request`), never a name or value the caller gave.
     * Never retried.
     */
    data class InvalidRequest(
        val field: String,
    ) : NetError
}
