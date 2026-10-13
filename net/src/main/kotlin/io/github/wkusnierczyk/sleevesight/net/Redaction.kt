package io.github.wkusnierczyk.sleevesight.net

import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLParameter

/** Where `:net` writes its log lines, already redacted. The callers route them to diagnostics. */
fun interface NetLog {
    fun log(line: String)

    companion object {
        val NONE: NetLog = NetLog { }
    }
}

/**
 * The redacted form of a request, for the log (architecture, Networking: never a header carrying a
 * key, or a query parameter named like one). Three layers, so that one mistake is not a leak:
 *
 * 1. Every value in [NetRequest.secrets] is redacted, whatever its name.
 * 2. Every other header or parameter whose name looks like a key is redacted ([isSensitive]), including
 *    parameters written into the URL itself; so are user and password in the URL.
 * 3. The line is percent-decoded, so a partly encoded key (`%73k-…`) is plain, and then the secrets'
 *    material (each value, and each token inside a header value such as `Bearer <token>`) is
 *    replaced wherever it appears, plain or encoded.
 *
 * A URL that does not parse is not shown at all.
 */
object Redaction {
    const val REDACTED: String = "REDACTED"

    private const val INVALID_URL = "<invalid url>"
    private const val MAX_DECODE_ROUNDS = 3

    private val SENSITIVE_PARTS =
        listOf("key", "token", "secret", "password", "passwd", "auth", "signature", "session", "cookie", "credential")
    private val SENSITIVE_NAMES = setOf("sk", "sig", "api_sig", "code", "code_verifier")

    /** Whether a header or parameter called [name] is taken to carry a key, token or password. */
    fun isSensitive(name: String): Boolean {
        val lower = name.lowercase()
        return lower in SENSITIVE_NAMES || SENSITIVE_PARTS.any { it in lower }
    }

    /** The method, the URL with its query, and the request's own headers, all redacted. */
    fun describe(request: NetRequest): String {
        val headers =
            request.headers.map { (name, value) -> name to if (isSensitive(name)) REDACTED else value } +
                request.secrets.headers.keys
                    .map { it to REDACTED }
        val line =
            buildString {
                append(request.method.name).append(' ').append(redactedUrl(request))
                if (headers.isNotEmpty()) {
                    append(headers.joinToString(prefix = " {", postfix = "}") { (name, value) -> "$name: $value" })
                }
            }
        return scrub(line, request.secrets.material)
    }

    private fun redactedUrl(request: NetRequest): String {
        val url = parseHttpUrl(request.url) ?: return INVALID_URL
        val builder = URLBuilder(url)
        builder.user = null
        builder.password = null
        builder.fragment = ""
        val inUrl = builder.parameters.build()
        builder.parameters.clear()
        inUrl.forEach { name, values -> values.forEach { builder.parameters.append(name, redact(name, it)) } }
        request.query.forEach { (name, value) -> builder.parameters.append(name, redact(name, value)) }
        request.secrets.query.keys
            .forEach { builder.parameters.append(it, REDACTED) }
        return builder.buildString()
    }

    private fun redact(
        name: String,
        value: String,
    ): String = if (isSensitive(name)) REDACTED else value

    private fun scrub(
        line: String,
        secrets: Collection<String>,
    ): String =
        secrets
            .filter { it.isNotEmpty() }
            .flatMap { listOf(it, it.encodeURLParameter()) }
            .sortedByDescending { it.length }
            .fold(decoded(line)) { acc, secret -> acc.replace(secret, REDACTED) }

    /**
     * [line] with every percent-escape of a visible ASCII character decoded, repeatedly, so no
     * encoding of a key, partial or double, hides it. Lenient: a stray `%` stays as it is.
     */
    private fun decoded(line: String): String {
        var current = line
        repeat(MAX_DECODE_ROUNDS) {
            val next =
                ESCAPE.replace(current) { match ->
                    val code = match.groupValues[1].toInt(HEX)
                    if (code in VISIBLE) code.toChar().toString() else match.value
                }
            if (next == current) return current
            current = next
        }
        return current
    }

    private val ESCAPE = Regex("%([0-9A-Fa-f]{2})")
    private const val HEX = 16
    private const val FIRST_VISIBLE = 0x20
    private const val DEL = 0x7f
    private val VISIBLE = FIRST_VISIBLE until DEL
}
