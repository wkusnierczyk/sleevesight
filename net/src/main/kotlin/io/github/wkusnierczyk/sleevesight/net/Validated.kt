package io.github.wkusnierczyk.sleevesight.net

import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.parseUrl

/**
 * Checks a request before Ktor sees it. Ktor throws on a malformed URL, header or media type with a
 * message that quotes the value, which may be a key; here the problem becomes
 * [NetError.InvalidRequest] naming only the field.
 */
internal sealed interface Validated {
    data class Valid(
        val url: Url,
    ) : Validated

    data class Invalid(
        val field: String,
    ) : Validated
}

internal fun validate(request: NetRequest): Validated {
    val url = parseHttpUrl(request.url)
    val badHeader =
        (request.headers + request.secrets.headers).entries.firstOrNull { (name, value) ->
            !isToken(name) || !isHeaderValue(value)
        }
    val badBody = request.body != null && parseContentType(request.body.contentType) == null
    return when {
        url == null || !isPlainHost(url.host) -> Validated.Invalid("url")
        badHeader != null -> Validated.Invalid(if (isToken(badHeader.key)) "header ${badHeader.key}" else "header name")
        badBody -> Validated.Invalid("body content type")
        request.timeout?.isPositive() == false -> Validated.Invalid("timeout")
        request.maxBytes <= 0 -> Validated.Invalid("maxBytes")
        else -> Validated.Valid(url)
    }
}

/** [text] as an absolute http(s) URL with a host, or null. Never throws. */
internal fun parseHttpUrl(text: String): Url? =
    parseUrl(text)?.takeIf {
        (it.protocol == URLProtocol.HTTPS || it.protocol == URLProtocol.HTTP) &&
            it.host.isNotEmpty()
    }

/**
 * The URL a request is sent to: [url] with the request's query parameters and secrets added, and no
 * user info or fragment, which are never sent.
 */
internal fun effectiveUrl(
    url: Url,
    request: NetRequest,
): Url =
    URLBuilder(url)
        .apply {
            user = null
            password = null
            fragment = ""
            (request.query + request.secrets.query).forEach { (name, value) -> parameters.append(name, value) }
        }.build()

/**
 * A host written as the transport will send it: lowercase ASCII letters, digits, dots and hyphens,
 * or a bracketed IPv6 literal. A percent-encoded or non-ASCII spelling (`%6dusicbrainz.org`,
 * `ｍusicbrainz.org`) is refused, since OkHttp would canonicalise it to a host whose rate limit and
 * origin `:net` would not recognise.
 */
private fun isPlainHost(host: String): Boolean =
    PLAIN_HOST.matches(host.lowercase()) || IPV6_LITERAL.matches(host.lowercase())

private val PLAIN_HOST = Regex("[a-z0-9.-]+")
private val IPV6_LITERAL = Regex("\\[[0-9a-f:.]+]")

@Suppress("SwallowedException") // The message quotes the value; the field name is reported instead.
internal fun parseContentType(text: String): ContentType? =
    if (!isHeaderValue(text)) {
        null
    } else {
        try {
            ContentType.parse(text)
        } catch (e: BadContentTypeFormatException) {
            null
        }
    }

/** An HTTP token (RFC 9110, 5.6.2): what a header name may be. */
private fun isToken(name: String): Boolean =
    name.isNotEmpty() && name.all { it.isLetterOrDigit() && it.code < ASCII_END || it in TCHAR }

/**
 * Visible ASCII, space and tab, which OkHttp accepts: a key read with its trailing newline fails
 * here. OkHttp's own check quotes the value in its message.
 */
private fun isHeaderValue(value: String): Boolean = value.all { it == '\t' || it.code in SPACE until DEL }

private const val TCHAR = "!#$%&'*+-.^_`|~"
private const val SPACE = 0x20
private const val DEL = 0x7f
private const val ASCII_END = 0x80
