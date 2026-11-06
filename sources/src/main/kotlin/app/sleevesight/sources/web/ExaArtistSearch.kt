package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.WebPage
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetResult
import app.sleevesight.net.fetch
import kotlin.time.Clock

/** A web search for the pages about an artist (#912): `ExaArtistSearch`; a fake in tests. */
interface ArtistWebSearch {
    /**
     * The web lines about the [artist], at most [ExaWebSearch.MAX_LINES], none when nothing found is
     * about it; failed when the engine could not be reached or refused the request. [sent] is told
     * right before the request goes out, the paid part, and not when none is sent.
     */
    suspend fun search(
        artist: String,
        sent: () -> Unit = {},
    ): Lookup<List<EvidenceDraft>>

    /**
     * The line a [page] the AI service's own search found gives about the [artist] (#912), by
     * [search]'s rules; null when it gives none. As the page came by default.
     */
    fun line(
        artist: String,
        page: WebPage,
    ): String? = page.text
}

/**
 * Exa's search for an artist's pages (#912), for the artist's insight when its sources say nothing
 * about the artist (`ArtistWebSource`), on the user's own key, as the album's ([ExaAlbumSearch]) is:
 * the same request but for the query ([ExaSearchRequest.ofArtist]: the artist's name as a music
 * artist, nothing of the track playing), the same host and policy ([ExaWebSearch.HOST_POLICY]), and
 * the same lines ([ExaWebSearch.lines]: Exa's highlights without the parts that are only page
 * boilerplate, the artist as the subject ([WebBoilerplate]), cut to [ExaWebSearch.LINE_CHARACTERS],
 * at most [ExaWebSearch.MAX_LINES], with the result's URL), of the results about the artist
 * ([RecordingFilter.isAboutArtist]: the artist named). Each line is a web line about the artist
 * (scope artist), which *Sources* shows as the artist's.
 *
 * Each [search] is one paid search: `ArtistWebSource` asks only before the artist's insight is
 * written, and keeps what it found. An artist whose name cannot be checked
 * ([RecordingFilter.canCheckArtist]) is not searched: nothing is sent, and nothing found.
 */
class ExaArtistSearch(
    private val net: NetClient,
    private val key: Secret,
    accentFolder: AccentFolder,
    private val clock: Clock = Clock.System,
) : ArtistWebSearch {
    private val filter = RecordingFilter(accentFolder)

    override suspend fun search(
        artist: String,
        sent: () -> Unit,
    ): Lookup<List<EvidenceDraft>> {
        if (!filter.canCheckArtist(artist)) return Lookup.Found(emptyList())
        val request = ExaWebSearch.request(ExaSearchRequest.ofArtist(artist), key)
        sent()
        return when (val fetched = net.fetch(request, ExaWebSearch::parse)) {
            is NetResult.Failure -> Lookup.Failed(UNREACHABLE)
            is NetResult.Success ->
                fetched.value?.let { response ->
                    Lookup.Found(
                        ExaWebSearch
                            .lines(response, clock.now(), listOf(artist), filter::fold) { result, snippet ->
                                filter.isAboutArtist(artist, result.title, snippet, result.url)
                            }.lines
                            .map { it.copy(scope = Scope.ARTIST) },
                    )
                } ?: Lookup.Failed(REFUSED)
        }
    }

    /**
     * The line [page] gives for [artist] by [search]'s rules (#912's review): judged on its passage
     * cut as a line is ([ExaWebSearch.cut]), kept only when about the artist
     * ([RecordingFilter.isAboutArtist], no source the app reads itself), the parts that are only
     * page boilerplate left out ([WebBoilerplate]), then cut to [ExaWebSearch.LINE_CHARACTERS];
     * null when it is not about the artist, or nothing is left. A page's passage is at most
     * `ExaPageSearch.PAGE_CHARACTERS` long, so its boilerplate is judged on that much of it.
     */
    override fun line(
        artist: String,
        page: WebPage,
    ): String? {
        if (!filter.isAboutArtist(artist, page.title, ExaWebSearch.cut(page.text), page.url)) return null
        return ExaWebSearch
            .cut(
                WebBoilerplate.clean(page.text, page.url, listOf(artist), filter::fold),
            ).ifEmpty { null }
    }

    private companion object {
        const val UNREACHABLE = "Exa could not be reached"
        const val REFUSED = "Exa refused the search"
    }
}
