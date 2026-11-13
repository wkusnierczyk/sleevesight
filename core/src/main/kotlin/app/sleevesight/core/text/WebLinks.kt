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
     */
    fun of(url: String?): String? =
        url?.trim()?.takeIf(::isWebAddress)?.let { address ->
            address.substringBefore("://").lowercase() + "://" + address.substringAfter("://")
        }

    // Whether [address], trimmed, is `http` or `https`, with a host, and nothing that does not show.
    private fun isWebAddress(address: String): Boolean {
        val scheme = address.substringBefore("://", "")
        val host =
            address
                .substringAfter("://", "")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('@')
                .substringBefore(':')
        val plain = address.none { it.isWhitespace() || it.isISOControl() }
        return scheme.lowercase() in SCHEMES && host.isNotEmpty() && plain
    }

    private val SCHEMES = setOf("http", "https")
}
