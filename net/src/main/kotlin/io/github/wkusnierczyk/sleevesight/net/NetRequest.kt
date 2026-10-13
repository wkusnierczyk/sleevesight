package io.github.wkusnierczyk.sleevesight.net

import kotlin.time.Duration

/** The HTTP methods the sources and providers use. */
enum class NetMethod {
    GET,
    POST,
    ;

    /** A request that can be repeated without effect beyond the first, so it is safe to retry. */
    val idempotent: Boolean get() = this == GET
}

/** A request body: text, such as JSON, with its media type. Sent once per attempt, never replayed by the engine. */
class NetBody(
    val text: String,
    val contentType: String,
)

/**
 * One HTTP request, in `:net`'s own terms, so the modules above it never see Ktor.
 *
 * Keys and tokens go in [secrets]. The log never shows a header or query value at all (see
 * [Redaction]), so the difference is what `:net` does with them: secrets, and headers named like
 * keys, stay behind on a redirect to another origin. [timeout], when set, replaces
 * [Timeouts.request] for this request, for a long stream or a slow model. A response body longer
 * than [maxBytes] is [NetError.TooLarge]. [toString] is the redacted form the log prints.
 */
@Suppress("LongParameterList") // Eight independent parts of a request, all but the URL defaulted.
class NetRequest(
    val url: String,
    val method: NetMethod = NetMethod.GET,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val secrets: NetSecrets = NetSecrets.NONE,
    val body: NetBody? = null,
    val timeout: Duration? = null,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    override fun toString(): String = Redaction.describe(this)

    companion object {
        /** Enough for any API response and for album art, and a bound on what a stranger's URI can make us load. */
        const val DEFAULT_MAX_BYTES: Long = 16L * 1024 * 1024
    }
}

/**
 * The query parameters and headers of a request that carry keys or tokens. Adapters pass
 * `Secret.reveal()` here and nowhere else.
 *
 * On a redirect to another origin they are dropped, but their credential material is [carried] on,
 * so that the log still hides it, and a redirect that would send it elsewhere is not followed.
 */
class NetSecrets internal constructor(
    val query: Map<String, String>,
    val headers: Map<String, String>,
    internal val carried: Set<String>,
) {
    constructor(
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ) : this(query, headers, emptySet())

    /** The secrets that follow a redirect: none to another origin; the headers to the same one. */
    internal fun forRedirect(
        sameOrigin: Boolean,
        material: Set<String>,
    ): NetSecrets = NetSecrets(emptyMap(), if (sameOrigin) headers else emptyMap(), material)

    override fun toString(): String = "NetSecrets(query=${query.keys}, headers=${headers.keys})"

    companion object {
        val NONE: NetSecrets = NetSecrets()
    }
}

/**
 * A response that is not an error (see [NetError]): any status other than 401, 403, 429 and 5xx,
 * and a 3xx only when it was not followed (see [NetClient.send]). A 404 is a response, not an
 * error, since what it means depends on the source.
 *
 * [bytes] is the body as received, for images; [body] is it decoded as UTF-8, which every source and
 * provider serves.
 */
class NetResponse(
    val status: Int,
    headers: Map<String, List<String>>,
    val bytes: ByteArray,
) {
    private val headers: Map<String, List<String>> = headers.mapKeys { it.key.lowercase() }

    val body: String by lazy { bytes.decodeToString() }

    /** The first value of the header [name], matched case-insensitively. */
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    val isSuccess: Boolean get() = status in SUCCESS

    override fun toString(): String = "NetResponse($status, ${bytes.size} bytes)"

    private companion object {
        val SUCCESS = 200..299
    }
}
