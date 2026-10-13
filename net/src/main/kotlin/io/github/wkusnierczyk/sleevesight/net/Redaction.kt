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
 * 3. Any secret value still found in the line, plain or URL-encoded, is replaced.
 */
object Redaction {
    const val REDACTED: String = "REDACTED"

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
        return scrub(line, request.secrets.query.values + request.secrets.headers.values)
    }

    private fun redactedUrl(request: NetRequest): String {
        val builder = URLBuilder(request.url)
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
            .fold(line) { acc, secret -> acc.replace(secret, REDACTED) }
}
