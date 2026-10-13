package io.github.wkusnierczyk.sleevesight.net

/** The HTTP methods the sources and providers use. */
enum class NetMethod {
    GET,
    POST,
    ;

    /** A request that can be repeated without effect beyond the first, so it is safe to retry. */
    val idempotent: Boolean get() = this == GET
}

/** A request body: text, such as JSON, with its media type. */
class NetBody(
    val text: String,
    val contentType: String,
)

/**
 * One HTTP request, in `:net`'s own terms, so the modules above it never see Ktor.
 *
 * Keys and tokens go in [secrets], which the log always redacts, whatever their names. [query] and
 * [headers] are logged, except values whose names look like keys (see [Redaction]). [toString] is the
 * redacted form the log prints.
 */
class NetRequest(
    val url: String,
    val method: NetMethod = NetMethod.GET,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val secrets: NetSecrets = NetSecrets.NONE,
    val body: NetBody? = null,
) {
    override fun toString(): String = Redaction.describe(this)
}

/**
 * The query parameters and headers of a request that carry keys or tokens. Adapters pass
 * `Secret.reveal()` here and nowhere else; `:net` sends the values and never logs them.
 */
class NetSecrets(
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "NetSecrets(query=${query.keys}, headers=${headers.keys})"

    companion object {
        val NONE: NetSecrets = NetSecrets()
    }
}

/**
 * A response that is not an error (see [NetError]): any status other than 401, 403, 429 and 5xx.
 * A 404 is a response, not an error, since what it means depends on the source.
 */
class NetResponse(
    val status: Int,
    headers: Map<String, List<String>>,
    val body: String,
) {
    private val headers: Map<String, List<String>> = headers.mapKeys { it.key.lowercase() }

    /** The first value of the header [name], matched case-insensitively. */
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    val isSuccess: Boolean get() = status in SUCCESS

    override fun toString(): String = "NetResponse($status, ${body.length} chars)"

    private companion object {
        val SUCCESS = 200..299
    }
}
