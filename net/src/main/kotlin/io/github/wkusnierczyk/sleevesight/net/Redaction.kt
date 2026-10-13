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
 * The log form of a request: only what is provably public. Every line shows the method, scheme,
 * host and port, and the number of headers; never a header name, a secret's name or any value, nor
 * user info. A public hop also shows its path and its query parameter names; a credential-bearing
 * one ([carriesCredentials]) shows its path as [HIDDEN_PATH] and no query names at all. A URL that
 * does not parse is not shown.
 */
object Redaction {
    /** The path of a credential-bearing request, as the log shows it. */
    const val HIDDEN_PATH: String = "/…"

    private const val INVALID_URL = "<invalid url>"

    /**
     * Name tokens taken to mark a key, token or password. A name matches, whatever its case, if any of
     * its tokens is one of these. Its tokens are the union of the lowercased name split on every run
     * of other characters than letters and digits and at letter/digit boundaries (`X-Api-KeY`,
     * `auth[token]`, `api key`, `key2`), and the name as written split the same way and at camelCase
     * boundaries, lowercased (`accessToken`, `sessionId`). `author`, `keyword` and `monkey` do not match.
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

    private val NOT_ALPHANUMERIC = Regex("[^A-Za-z0-9]+")
    private val LETTER_DIGIT = Regex("([A-Za-z])([0-9])")
    private val DIGIT_LETTER = Regex("([0-9])([A-Za-z])")
    private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")

    /** Whether a header or parameter called [name] is taken to carry a key, token or password. */
    fun isSensitive(name: String): Boolean {
        fun tokens(text: String) =
            text
                .replace(LETTER_DIGIT, "$1 $2")
                .replace(DIGIT_LETTER, "$1 $2")
                .split(NOT_ALPHANUMERIC)
                .map { it.lowercase() }
        val all = tokens(name.lowercase()) + tokens(name.replace(CAMEL_BOUNDARY, "$1 $2"))
        return all.any { it in SENSITIVE_TOKENS }
    }

    /** [request] as the log shows it, its query parameters added to its URL. */
    fun describe(request: NetRequest): String =
        describe(request, parseHttpUrl(request.url)?.let { effectiveUrl(it, request) }, carriesCredentials(request))

    /** [request], sent to [url] (null if it does not parse); [credentials] if the hop is credential-bearing. */
    internal fun describe(
        request: NetRequest,
        url: Url?,
        credentials: Boolean,
    ): String {
        val target =
            if (url == null) {
                INVALID_URL
            } else {
                buildString {
                    append(url.protocol.name).append("://").append(url.host)
                    if (url.port != url.protocol.defaultPort) append(':').append(url.port)
                    if (credentials) {
                        append(HIDDEN_PATH)
                    } else {
                        append(url.encodedPath)
                        val names = url.parameters.names()
                        if (names.isNotEmpty()) append(names.joinToString("&", prefix = "?"))
                    }
                }
            }
        val headers = request.headers.size + request.secrets.headers.size
        return "${request.method.name} $target {$headers ${if (headers == 1) "header" else "headers"}}"
    }
}
