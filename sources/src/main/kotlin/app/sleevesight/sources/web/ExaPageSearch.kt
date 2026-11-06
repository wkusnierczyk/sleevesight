package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.sources.MUSICBRAINZ_HOST
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.sources.isMusicBrainzPage
import app.sleevesight.net.NetBody
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetMethod
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResult
import app.sleevesight.net.NetSecrets
import app.sleevesight.net.fetch
import kotlinx.serialization.Serializable

/**
 * *AI web search*'s searches through Exa (#801; `design/design.md`, *Ask AI*), on the user's own key,
 * as the web search source's ([ExaWebSearch]) are: `POST /search`, the key in a header through
 * [NetSecrets], the same host and its policy ([ExaWebSearch.HOST_POLICY]). Each [search] is one
 * paid search for a query in words, which the app builds or the model writes; its results are the
 * question's pages, each with its highlights for the query, whitespace collapsed, the parts that
 * are only page boilerplate for the search's subject left out ([WebBoilerplate], #929, as the
 * source's lines, #905), and cut to [PAGE_CHARACTERS] at a word boundary ([ExaWebSearch.snippetOf]).
 *
 * Unlike the source's, the results are not filtered: they are not evidence about the recording but
 * pages for the model to answer from, and a value it gives from one is kept only with that page
 * named and among those found (`AiSearchAnswers`). One domain is left out, MusicBrainz's, in the
 * request and again from what comes back ([isMusicBrainzPage]): its pages carry its tags, which
 * never reach a prompt (D12). Discogs and Wikipedia are kept, which the source leaves out as
 * sources of their own. A failure (the network, a status
 * other than 2xx) is [WebSearchOutcome.Failed]; a page with no highlight, or whose highlights are
 * all boilerplate, is left out, so a search whose pages all are found nothing.
 */
class ExaPageSearch(
    private val net: NetClient,
    private val key: Secret,
    accentFolder: AccentFolder,
) : WebSearcher {
    // Folds the subject's names and a passage's words as the source's filter does ([WebBoilerplate]).
    private val filter = RecordingFilter(accentFolder)

    override suspend fun search(
        query: String,
        subject: List<String>?,
    ): WebSearchOutcome =
        when (val fetched = net.fetch(request(query), ExaWebSearch::parse)) {
            is NetResult.Failure -> WebSearchOutcome.Failed
            is NetResult.Success ->
                fetched.value?.let { WebSearchOutcome.Found(pagesOf(it, subject)) }
                    ?: WebSearchOutcome.Failed
        }

    /** The search request for [query]: the key in a header, through [NetSecrets] only. */
    internal fun request(query: String): NetRequest =
        NetRequest(
            url = ExaWebSearch.ENDPOINT,
            method = NetMethod.POST,
            secrets = NetSecrets(headers = mapOf(ExaWebSearch.KEY_HEADER to key.reveal())),
            body = NetBody(ExaWebSearch.JSON.encodeToString(ExaPageRequest.of(query)), "application/json"),
        )

    // Each result with a highlight not all boilerplate for [subject] (null: not cleaned), once per
    // address, in Exa's order.
    private fun pagesOf(
        response: ExaSearchResponse,
        subject: List<String>?,
    ): List<WebPage> {
        val seen = mutableSetOf<String>()
        return response.results.mapNotNull { result ->
            val text =
                ExaWebSearch.snippetOf(result, PAGE_CHARACTERS) {
                    subject?.let { names -> WebBoilerplate.clean(it, result.url, names, filter::fold) } ?: it
                }
            WebPage(result.url, result.title, text).takeIf {
                text.isNotEmpty() && !isMusicBrainzPage(result.url) && seen.add(result.url)
            }
        }
    }

    companion object {
        /** The results asked for: the most the base price covers, as the source asks. */
        const val RESULTS: Int = 10

        /**
         * The most characters of a page's passage: more than a source's line (300), since the model
         * answers from it, and Exa bills no more for a longer highlight.
         */
        const val PAGE_CHARACTERS: Int = 600
    }
}

/**
 * The body of *AI web search*'s `POST /search` (#801): the query as given, Exa's cheapest type,
 * `lite`, as the source's ([ExaSearchRequest]), [ExaPageSearch.RESULTS] results, MusicBrainz's
 * domain left out (D12), and highlights steered by the same query, at [ExaPageSearch.PAGE_CHARACTERS].
 */
@Serializable
internal data class ExaPageRequest(
    val query: String,
    val type: String = "lite",
    val numResults: Int = ExaPageSearch.RESULTS,
    val excludeDomains: List<String> = listOf(MUSICBRAINZ_HOST),
    val contents: ExaContents,
) {
    companion object {
        fun of(query: String): ExaPageRequest =
            ExaPageRequest(query = query, contents = ExaContents(ExaHighlights(query, ExaPageSearch.PAGE_CHARACTERS)))
    }
}
