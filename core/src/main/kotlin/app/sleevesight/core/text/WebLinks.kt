package app.sleevesight.core.text

/**
 * The web addresses the app opens (#1040). A source's links come from web pages and Last.fm, so a
 * link may carry any scheme: `intent:`, `javascript:`, `file:`, `market:`, `tel:` … A link the app
 * would hand to the system could then open another app, or one that cannot be opened, so only `http`
 * and `https` addresses are kept: [of] drops the rest where a source is read, and the app's one way
 * to open a link (`openWebLink`) checks again before it opens anything.
 */
object WebLinks {
    /**
     * [url] when it is a web address: `http://` or `https://` (the scheme in any case), then a host,
     * and no white space or control character anywhere; else null. Spaces around it are trimmed, and
     * the scheme is given in lower case (`HTTPS://` as `https://`): Android matches an intent's scheme
     * case-sensitively, so a browser would not take a link with its scheme in capitals.
     *
     * An address written in a form browsers read differently from how it looks is refused too (#1245):
     * one with a backslash anywhere, or with user info (an `@` before its host), so the host that
     * [host] names is the one the browser opens. A backslash only in the path, query or fragment is
     * refused on purpose as well: Android's `Uri` has handled `\` differently across versions.
     */
    fun of(url: String?): String? =
        url?.trim()?.takeIf(::isWebAddress)?.let { address ->
            address.substringBefore("://").lowercase() + "://" + address.substringAfter("://")
        }

    /**
     * The host of the web address [url] (`http` or `https`, the scheme in any case), read as browsers
     * read it (the WHATWG URL standard, #1245): after the scheme and any slashes, the authority runs to
     * the first `/`, `\`, `?` or `#`; the host is what follows its last `@`, up to a port; an IPv6
     * address keeps its brackets. Its ASCII letters in lower case, any other character as written
     * (Unicode casing could give a name the address does not say). Null when [url] is not a web
     * address, has no host, has a white space or control character in it, or a `%` in its host. Every
     * label that names a link's site reads the host here, so it names the site the link opens.
     */
    fun host(url: String?): String? =
        url
            ?.trim()
            ?.takeIf { it.isPlain() && it.substringBefore(':', "").lowercase() in SCHEMES }
            ?.let { address ->
                hostIn(address.substringAfter(':').trimStart('/', '\\').takeWhile { it !in AUTHORITY_ENDS })
            }

    // Whether [address], trimmed, is `http` or `https`, with a host right after `://`, no user info,
    // no backslash, and nothing that does not show.
    private fun isWebAddress(address: String): Boolean {
        val scheme = address.substringBefore("://", "")
        val authority = address.substringAfter("://", "").takeWhile { it !in AUTHORITY_ENDS }
        return scheme.lowercase() in SCHEMES &&
            address.isPlain() &&
            '\\' !in address &&
            '@' !in authority &&
            hostIn(authority) != null
    }

    // The host in [authority] (`user@host:port`): after its last `@`, before its port, ASCII in lower case;
    // an IPv6 address in its brackets. Null when empty, malformed, or with a `%` in it.
    private fun hostIn(authority: String): String? {
        val hostAndPort = authority.substringAfterLast('@')
        val host =
            if (hostAndPort.startsWith('[')) {
                val inside = hostAndPort.substring(1).substringBefore(']', "")
                if (inside.isEmpty() || !inside.all { it in IPV6_CHARS }) return null
                "[$inside]"
            } else {
                hostAndPort.substringBefore(':')
            }
        // ASCII letters only: Unicode casing could give a name the address does not say.
        return host.map { if (it in 'A'..'Z') it + ('a' - 'A') else it }.joinToString("").takeIf {
            it.isNotEmpty() && '%' !in it
        }
    }

    private fun String.isPlain(): Boolean = none { it.isWhitespace() || it.isISOControl() }

    private val SCHEMES = setOf("http", "https")

    // Where an address's authority ends, as browsers read an `http` or `https` address.
    private const val AUTHORITY_ENDS = "/\\?#"

    private const val IPV6_CHARS = "0123456789abcdefABCDEF:."
}
