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
 * A web search engine a question to the AI service searches with (*AI web search*, #801;
 * `design/design.md`, *Ask AI*): each [search] is one paid search for [query], a query in words,
 * which the app builds (*Once*) or the model writes (*Up to {n} times*). Exa's is
 * `ExaPageSearch`, in `:sources`, on the user's key.
 */
fun interface WebSearcher {
    suspend fun search(query: String): WebSearchOutcome
}
