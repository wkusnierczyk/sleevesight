package io.github.wkusnierczyk.sleevesight.net

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the one client behaves: the User-Agent, the per-host rate limits, timeouts and retries.
 *
 * [appVersion] is the version of the app or tool making the requests, for the User-Agent.
 */
class NetConfig(
    val appVersion: String,
    val hosts: Map<String, HostPolicy> = DEFAULT_HOSTS,
    val timeouts: Timeouts = Timeouts(),
    val retry: RetryPolicy = RetryPolicy(),
) {
    /** In the form MusicBrainz asks for: application, version, and a contact in parentheses. */
    val userAgent: String get() = "Sleevesight/$appVersion ( $CONTACT )"

    /** The policy for [host]: an entry for the host itself or for a domain it belongs to. */
    fun policyFor(host: String): Pair<String, HostPolicy>? =
        hosts.entries
            .filter { (domain, _) -> host == domain || host.endsWith(".$domain") }
            .maxByOrNull { it.key.length }
            ?.toPair()

    companion object {
        /** MusicBrainz requires a contact in the User-Agent; a URL is allowed (owner, 2026-10-13). */
        const val CONTACT: String = "https://github.com/wkusnierczyk/sleevesight"

        const val MUSICBRAINZ: String = "musicbrainz.org"

        /**
         * MusicBrainz allows one request per second per client. The other sources state no hard
         * limit the pipeline comes near; their adapters add an entry when one applies.
         */
        val DEFAULT_HOSTS: Map<String, HostPolicy> = mapOf(MUSICBRAINZ to HostPolicy(minInterval = 1.seconds))
    }
}

/** The rate limit for one host: requests to it start at least [minInterval] apart. */
data class HostPolicy(
    val minInterval: Duration,
)

/** Ktor's three timeouts: to connect, between two packets, and for the whole request. */
data class Timeouts(
    val connect: Duration = 10.seconds,
    val socket: Duration = 30.seconds,
    val request: Duration = 60.seconds,
)

/**
 * Retries of a failed attempt: at most [maxAttempts] attempts in all, waiting [initialDelay] and then
 * twice as long each time, up to [maxDelay]. A `Retry-After` from the server replaces the wait; one
 * longer than [maxDelay] ends the retries.
 */
data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelay: Duration = 500.milliseconds,
    val maxDelay: Duration = 10.seconds,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
    }

    /** The wait before attempt [attempt] + 1. */
    fun delayAfter(attempt: Int): Duration {
        var delay = initialDelay
        repeat(attempt - 1) { delay = (delay * 2).coerceAtMost(maxDelay) }
        return delay.coerceAtMost(maxDelay)
    }
}
