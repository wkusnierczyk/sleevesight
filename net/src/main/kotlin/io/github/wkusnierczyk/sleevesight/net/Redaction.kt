package io.github.wkusnierczyk.sleevesight.net

import io.ktor.http.Url

/** Where `:net` writes its log lines, already redacted. The callers route them to diagnostics. */
fun interface NetLog {
    fun log(line: String)

    companion object {
        val NONE: NetLog = NetLog { }
    }
}

/**
 * The log form of a request, redacted by construction rather than by matching (architecture,
 * Networking): the method, scheme, host and port, the path, and the *names* of the query parameters
 * and headers, never a value. User info is left out. The path is the one part that could still
 * carry a key (one echoed into a redirect's path), so for a request that carries credentials
 * ([carriesCredentials]) it is shown as [HIDDEN_PATH]. A URL that does not parse is not shown at all.
 */
object Redaction {
    /** The path of a request that carries credentials, as the log shows it. */
    const val HIDDEN_PATH: String = "/…"

    private const val INVALID_URL = "<invalid url>"

    /**
     * Name tokens taken to mark a key, token or password. A name is split into tokens on `-`, `_`,
     * `.` and lower-to-upper camelCase boundaries, and matches if any token is one of these, so
     * `x-goog-api-key` and `authToken` match and `author`, `keyword` and `monkey` do not.
     */
    val SENSITIVE_TOKENS: Set<String> =
        setOf(
            "key",
            "apikey",
            "token",
            "accesstoken",
            "authtoken",
            "secret",
            "password",
            "passwd",
            "auth",
            "authorization",
            "signature",
            "sig",
            "sk",
            "session",
            "cookie",
            "credential",
            "credentials",
            "code",
            "verifier",
            "bearer",
        )

    private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")
    private val SEPARATORS = Regex("[-_.]+")

    /** Whether a header or parameter called [name] is taken to carry a key, token or password. */
    fun isSensitive(name: String): Boolean =
        name
            .replace(CAMEL_BOUNDARY, "$1-$2")
            .split(SEPARATORS)
            .any { it.lowercase() in SENSITIVE_TOKENS }

    /** [request] as the log shows it, its query parameters added to its URL. */
    fun describe(request: NetRequest): String =
        describe(request, parseHttpUrl(request.url)?.let { effectiveUrl(it, request) }, carriesCredentials(request))

    /** [request], sent to [url] (null if it does not parse); its path hidden if it [carriesCredentials]. */
    internal fun describe(
        request: NetRequest,
        url: Url?,
        carriesCredentials: Boolean,
    ): String {
        val target =
            if (url == null) {
                INVALID_URL
            } else {
                buildString {
                    append(url.protocol.name).append("://").append(url.host)
                    if (url.port != url.protocol.defaultPort) append(':').append(url.port)
                    append(if (carriesCredentials) HIDDEN_PATH else url.encodedPath)
                    val names = url.parameters.names()
                    if (names.isNotEmpty()) append(names.joinToString("&", prefix = "?"))
                }
            }
        val headers = request.headers.keys + request.secrets.headers.keys
        val shownHeaders = if (headers.isEmpty()) "" else headers.joinToString(prefix = " {", postfix = "}")
        return "${request.method.name} $target$shownHeaders"
    }
}
