package io.github.wkusnierczyk.sleevesight.net

import io.ktor.http.URLDecodeException
import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLParameter

/**
 * The credential material of [request], collected before its first send and carried across its
 * redirects: the values of [NetRequest.secrets]; the values of headers and query parameters named
 * like keys ([Redaction.isSensitive]), in the URL or not; the URL's user and password; and, for each
 * header value of the form `<scheme> <credential>` (`Bearer x`), the credential, quotes stripped.
 *
 * Each value is taken whole, with no minimum length and no splitting, as given, percent-decoded once
 * and percent-encoded, so its common spellings in a URL all match.
 */
internal fun credentialMaterial(request: NetRequest): Set<String> {
    val url = parseHttpUrl(request.url)
    val headers = request.secrets.headers.values + request.headers.filterKeys(Redaction::isSensitive).values
    val values =
        buildList {
            addAll(request.secrets.query.values)
            addAll(request.query.filterKeys(Redaction::isSensitive).values)
            addAll(headers)
            headers.forEach { header -> schemeCredential(header)?.let(::add) }
            url
                ?.parameters
                ?.entries()
                ?.filter { Redaction.isSensitive(it.key) }
                ?.forEach { addAll(it.value) }
            url?.user?.let(::add)
            url?.password?.let(::add)
        }
    return request.secrets.carried +
        values.flatMap { listOf(it, decodeOnce(it), it.encodeURLParameter()) }.filter { it.isNotEmpty() }
}

/** Whether [text], as it is or percent-decoded once, contains any of [material]. */
internal fun containsMaterial(
    text: String,
    material: Set<String>,
): Boolean {
    val decoded = decodeOnce(text)
    return material.any { it in text || it in decoded }
}

/** [text] percent-decoded once, or as it is if it is not validly encoded. */
@Suppress("SwallowedException") // Not encoded is not an error here; the text is compared as it is.
internal fun decodeOnce(text: String): String =
    try {
        text.decodeURLPart()
    } catch (e: URLDecodeException) {
        text
    }

/** The credential after the scheme word of an `Authorization`-style value, unquoted, or null. */
private fun schemeCredential(value: String): String? =
    SCHEME_VALUE
        .matchEntire(value.trim())
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.removeSurrounding("\"")

private val SCHEME_VALUE = Regex("[A-Za-z][A-Za-z0-9!#$%&'*+.^_`|~-]*\\s+(.+)")
