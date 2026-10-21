package app.sleevesight.net

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the one client behaves: the User-Agent, the per-host rate limits, timeouts and retries.
 *
 * [appVersion] is the version of the app or tool making the requests, for the User-Agent. [hosts]
 * keys are domains, matched case-insensitively and without a trailing dot. The app and the CLI pass
 * every adapter's and provider's policy, from `:wiring`'s `HostPolicies`; the default,
 * [DEFAULT_HOSTS], is `:net`'s own list, for this module's tests and the live tests of a module that
 * cannot see `:wiring`.
 */
class NetConfig(
    val appVersion: String,
    hosts: Map<String, HostPolicy> = DEFAULT_HOSTS,
    val timeouts: Timeouts = Timeouts(),
    val retry: RetryPolicy = RetryPolicy(),
) {
    val hosts: Map<String, HostPolicy> = hosts.mapKeys { normalizeHost(it.key) }

    /** In the form MusicBrainz asks for: application, version, and a contact in parentheses. */
    val userAgent: String get() = "Sleevesight/$appVersion ( $CONTACT )"

    /** The configured domain [host] belongs to (the longest match), or null if it has no limit. */
    fun domainFor(host: String): String? {
        val normalized = normalizeHost(host)
        return hosts.keys
            .filter { domain -> normalized == domain || normalized.endsWith(".$domain") }
            .maxByOrNull { it.length }
    }

    companion object {
        /** MusicBrainz requires a contact in the User-Agent; a URL is allowed (owner, 2026-10-13). */
        const val CONTACT: String = "https://github.com/wkusnierczyk/sleevesight"

        const val MUSICBRAINZ: String = "musicbrainz.org"

        /**
         * MusicBrainz allows one request per second per client, and answers 503 beyond it, so its
         * policy reports a 503 to a GET as [NetError.RateLimited]. Last.fm's API terms
         * (checked 2026-10-13) say limits are set and enforced at its discretion, with no figure, and
         * Wikimedia asks for serial requests without a rate, so neither has an entry. Another
         * host's stated limit, such as Exa's or Discogs' (60 a minute, Phase 3), is added to
         * `:wiring`'s `HostPolicies`, beside these, not here.
         */
        val DEFAULT_HOSTS: Map<String, HostPolicy> =
            mapOf(MUSICBRAINZ to HostPolicy(minInterval = 1.seconds, rateLimitedOn503 = true))

        /** Host names compare lowercased and without the trailing dot of a fully qualified name. */
        fun normalizeHost(host: String): String = host.lowercase().trimEnd('.')
    }
}

/**
 * The rate limit for one host: requests to it start at least [minInterval] apart.
 * [rateLimitedOn503] is for a host that answers 503 when its limit is exceeded: a 503 to a GET is
 * then [NetError.RateLimited]. A POST's 503 stays [NetError.Server] and is not retried, since a 503,
 * unlike a 429, does not promise the request was not acted on.
 *
 * [passOn429] is for a host whose 429 does not always mean "too many requests": OpenAI answers 429
 * also when the account is out of credit or over its spending limit, which no retry mends, and says
 * which in the body. Its 429 is then passed on as a response, body and headers, for the caller to
 * read, and is not retried; without the flag a 429 is retried and then [NetError.RateLimited].
 */
data class HostPolicy(
    val minInterval: Duration,
    val rateLimitedOn503: Boolean = false,
    val passOn429: Boolean = false,
)

/**
 * Ktor's three timeouts: to connect, between two packets, and for the whole request.
 * [NetRequest.timeout] replaces [request] for one request.
 */
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
        require(initialDelay.isPositive()) { "initialDelay must be positive" }
        require(maxDelay >= initialDelay) { "maxDelay must be at least initialDelay" }
    }

    /** The wait before attempt [attempt] + 1. */
    fun delayAfter(attempt: Int): Duration {
        var delay = initialDelay
        repeat(attempt - 1) { delay = (delay * 2).coerceAtMost(maxDelay) }
        return delay
    }
}
