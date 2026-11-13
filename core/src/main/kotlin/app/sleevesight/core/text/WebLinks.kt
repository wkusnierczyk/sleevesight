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
     * and no white space or control character anywhere; else null. Spaces around it are trimmed.
     */
    fun of(url: String?): String? {
        val address = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
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
        return address.takeIf { scheme.lowercase() in SCHEMES && host.isNotEmpty() && plain }
    }

    /** Whether [url] is a web address the app opens ([of]). */
    fun isWeb(url: String?): Boolean = of(url) != null

    private val SCHEMES = setOf("http", "https")
}
