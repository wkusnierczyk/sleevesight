package app.sleevesight.net

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
 * user info. A hop the log does not hide ([hiddenInLog]) also shows its path and its query parameter
 * names, without the names in `secrets.query`; a hidden one shows its path as [HIDDEN_PATH] and no
 * query names at all. A URL that does not parse is not shown.
 */
object Redaction {
    /** The path of a hidden hop, as the log shows it. */
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
            "verifier",
            "bearer",
            "pwd",
            "passphrase",
            "jwt",
        )

    /**
     * Whole names, compared lowercased with everything but letters and digits removed, so that a
     * compound written without boundaries (`CLIENTSECRET`, `XAPIKEY`, `SESSIONID`) still matches.
     * `code` (the OAuth parameter) is here rather than among the tokens, so `languageCode` does not
     * match.
     */
    val SENSITIVE_NAMES: Set<String> =
        setOf(
            "apikey",
            "xapikey",
            "apitoken",
            "accesstoken",
            "authtoken",
            "clientsecret",
            "sessionid",
            "sessiontoken",
            "secretkey",
            "privatekey",
            "password",
            "passwd",
            "code",
            "devicecode",
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
        return all.any { it in SENSITIVE_TOKENS } || name.lowercase().replace(NOT_ALPHANUMERIC, "") in SENSITIVE_NAMES
    }

    /** [request] as the log shows it, its query parameters added to its URL. */
    fun describe(request: NetRequest): String =
        describe(request, parseHttpUrl(request.url)?.let { effectiveUrl(it, request) }, hiddenInLog(request))

    /** [request], sent to [url] (null if it does not parse); its path and query names [hidden] or not. */
    internal fun describe(
        request: NetRequest,
        url: Url?,
        hidden: Boolean,
    ): String {
        val target =
            if (url == null) {
                INVALID_URL
            } else {
                buildString {
                    append(url.protocol.name).append("://").append(url.host)
                    if (url.port != url.protocol.defaultPort) append(':').append(url.port)
                    if (hidden) {
                        append(HIDDEN_PATH)
                    } else {
                        append(url.encodedPath)
                        val names = url.parameters.names() - request.secrets.query.keys
                        if (names.isNotEmpty()) append(names.joinToString("&", prefix = "?"))
                    }
                }
            }
        val headers = request.headers.size + request.secrets.headers.size
        return "${request.method.name} $target {$headers ${if (headers == 1) "header" else "headers"}}"
    }
}
