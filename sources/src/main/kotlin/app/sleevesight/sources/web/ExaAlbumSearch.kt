package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetResult
import app.sleevesight.net.fetch
import kotlin.time.Clock

/** A web search for the pages about an album (#885): `ExaAlbumSearch`; a fake in tests. */
interface AlbumWebSearch {
    /**
     * The web lines about the [album] by [artist], at most [ExaWebSearch.MAX_LINES], none when
     * nothing found is about it; failed when the engine could not be reached or refused the request.
     * [sent] is told right before the request goes out, the paid part, and not when none is sent.
     */
    suspend fun search(
        album: String,
        artist: String,
        sent: () -> Unit = {},
    ): Lookup<List<EvidenceDraft>>
}

/**
 * Exa's search for an album's pages (#885), for an album's insight (`AlbumSource`), on the user's
 * own key, as the track's web search ([ExaWebSearch]) makes its own: the same request but for the
 * query ([ExaSearchRequest.ofAlbum]: the album's title and its first artist, as an album, nothing
 * of the track playing), the same host and policy ([ExaWebSearch.HOST_POLICY]), and the same lines
 * ([ExaWebSearch.lines]: Exa's highlights, cut to [ExaWebSearch.LINE_CHARACTERS], at most
 * [ExaWebSearch.MAX_LINES], with the result's URL), of the results about the album
 * ([RecordingFilter.isAboutAlbum]: its title and the artist named). Each line is a web line
 * (scope web) of the album's evidence.
 *
 * Each [search] is one paid search: `AlbumSource` asks only before the album's insight is written,
 * and keeps what it found. An album that cannot be checked ([RecordingFilter.canCheckAlbum]) is
 * not searched: nothing is sent, and nothing found.
 */
class ExaAlbumSearch(
    private val net: NetClient,
    private val key: Secret,
    accentFolder: AccentFolder,
    private val clock: Clock = Clock.System,
) : AlbumWebSearch {
    private val filter = RecordingFilter(accentFolder)

    override suspend fun search(
        album: String,
        artist: String,
        sent: () -> Unit,
    ): Lookup<List<EvidenceDraft>> {
        if (!filter.canCheckAlbum(album, artist)) return Lookup.Found(emptyList())
        val request = ExaWebSearch.request(ExaSearchRequest.ofAlbum(album, artist), key)
        sent()
        return when (val fetched = net.fetch(request, ExaWebSearch::parse)) {
            is NetResult.Failure -> Lookup.Failed(UNREACHABLE)
            is NetResult.Success ->
                fetched.value?.let { response ->
                    Lookup.Found(
                        ExaWebSearch.lines(response, clock.now()) { result, snippet ->
                            filter.isAboutAlbum(album, artist, result.title, snippet, result.url)
                        },
                    )
                } ?: Lookup.Failed(REFUSED)
        }
    }

    private companion object {
        const val UNREACHABLE = "Exa could not be reached"
        const val REFUSED = "Exa refused the search"
    }
}
