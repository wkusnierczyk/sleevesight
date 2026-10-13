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
 * The log form of a request, redacted by construction rather than by scrubbing (architecture,
 * Networking): the method, scheme, host and port, the path, and the *names* of the query parameters
 * and headers, never a value. User and password are left out. The only part that can still carry a
 * secret is the path (a key echoed into a redirect's path), so a path segment that contains
 * credential material ([credentialMaterial]), as written or percent-decoded once, is shown as
 * [REDACTED]; so is a host or a name that does. A URL that does not parse is not shown at all.
 */
object Redaction {
    const val REDACTED: String = "REDACTED"

    private const val INVALID_URL = "<invalid url>"

    private val SENSITIVE_PARTS =
        listOf("key", "token", "secret", "password", "passwd", "auth", "signature", "session", "cookie", "credential")
    private val SENSITIVE_NAMES = setOf("sk", "sig", "api_sig", "code", "code_verifier")

    /** Whether a header or parameter called [name] is taken to carry a key, token or password. */
    fun isSensitive(name: String): Boolean {
        val lower = name.lowercase()
        return lower in SENSITIVE_NAMES || SENSITIVE_PARTS.any { it in lower }
    }

    /** [request] as the log shows it, its query parameters added to its URL. */
    fun describe(request: NetRequest): String =
        describe(request, parseHttpUrl(request.url)?.let { effectiveUrl(it, request) }, credentialMaterial(request))

    /** [request], sent to [url] (null if it does not parse), with [material] hidden. */
    internal fun describe(
        request: NetRequest,
        url: Url?,
        material: Set<String>,
    ): String {
        fun shown(part: String) = if (containsMaterial(part, material)) REDACTED else part
        val target =
            if (url == null) {
                INVALID_URL
            } else {
                buildString {
                    append(url.protocol.name).append("://").append(shown(url.host))
                    if (url.port != url.protocol.defaultPort) append(':').append(url.port)
                    append(url.encodedPath.split('/').joinToString("/") { shown(it) })
                    val names = url.parameters.names()
                    if (names.isNotEmpty()) append(names.joinToString("&", prefix = "?") { shown(it) })
                }
            }
        val headers = (request.headers.keys + request.secrets.headers.keys).map { shown(it) }
        val shownHeaders = if (headers.isEmpty()) "" else headers.joinToString(prefix = " {", postfix = "}")
        return "${request.method.name} $target$shownHeaders"
    }
}
