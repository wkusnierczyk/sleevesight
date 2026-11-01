package app.sleevesight.core.sources

/**
 * A page a web search found (#801): its [url], its [title] where the engine gives one, and [text],
 * a passage of it the engine chose for the query (Exa's highlights), cut short.
 */
data class WebPage(
    val url: String,
    val title: String?,
    val text: String,
)

/** What one web search gave: the pages it found ([Found], none included), or nothing, as it [Failed]. */
sealed interface WebSearchOutcome {
    /** The search answered, with [pages] (none when it found nothing). */
    data class Found(
        val pages: List<WebPage>,
    ) : WebSearchOutcome

    /** The search failed: the engine could not be reached, or refused the request (a key, credit). */
    data object Failed : WebSearchOutcome
}

/**
 * Whether [url] is a page on MusicBrainz (`musicbrainz.org` or a subdomain of it), whose pages
 * carry its tags: a question's pages leave them out (D12, #801), so no tag reaches a prompt.
 */
fun isMusicBrainzPage(url: String): Boolean {
    val host =
        url
            .substringAfter("://", "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('@')
            .substringBefore(':')
            .lowercase()
            .trimEnd('.')
    return host == MUSICBRAINZ_HOST || host.endsWith(".$MUSICBRAINZ_HOST")
}

/** MusicBrainz's host, which a question's web search leaves out (D12). */
const val MUSICBRAINZ_HOST: String = "musicbrainz.org"

/**
 * A web search engine a question to the AI service searches with (*AI web search*, #801;
 * `design/design.md`, *Ask AI*): each [search] is one paid search for [query], a query in words,
 * which the app builds (*Once*) or the model writes (*Up to {n} times*). Exa's is
 * `ExaPageSearch`, in `:sources`, on the user's key.
 */
fun interface WebSearcher {
    suspend fun search(query: String): WebSearchOutcome
}
