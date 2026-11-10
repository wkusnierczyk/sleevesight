package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.normalizer.Edition
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
     * With [performers] (a track whose title names its composer, the [artist], #1130), the lines of
     * pages that name one of them (`RecordingFilter.isAboutAlbum`). [sent] is told right before the
     * request goes out, the paid part, and not when none is sent.
     */
    suspend fun search(
        album: String,
        artist: String,
        performers: List<String> = emptyList(),
        sent: () -> Unit = {},
    ): Lookup<List<EvidenceDraft>>
}

/**
 * Exa's search for an album's pages (#885), for an album's insight (`AlbumSource`), on the user's
 * own key, as the track's web search ([ExaWebSearch]) makes its own: the same request but for the
 * query ([ExaSearchRequest.ofAlbum]: the album's title and its first artist, every credited artist
 * with a composer the title names, as an album, nothing
 * of the track playing; the album's name without the edition a player or store adds in brackets,
 * `Edition.stripEditions`: "Abbey Road (Remastered)" is "Abbey Road", "Sundown (Live)" stays, which
 * the filter then asks a page to name too), the same host and policy ([ExaWebSearch.HOST_POLICY]),
 * and the same lines ([ExaWebSearch.lines]: Exa's highlights without the parts that are only page
 * boilerplate, the album and its artist as the subject ([WebBoilerplate]), cut to
 * [ExaWebSearch.LINE_CHARACTERS],
 * at most [ExaWebSearch.MAX_LINES], with the result's URL), of the results about the album
 * ([RecordingFilter.isAboutAlbum]: its title and the artist named, or with a composer the title
 * names, a performer, #1130). Each line is a web line
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
        performers: List<String>,
        sent: () -> Unit,
    ): Lookup<List<EvidenceDraft>> {
        // Without the edition a player or store adds, which few pages about the album name (#885).
        val name = Edition.stripEditions(album)
        if (!filter.canCheckAlbum(name, artist, performers)) return Lookup.Found(emptyList())
        val request = ExaWebSearch.request(ExaSearchRequest.ofAlbum(name, artist, performers), key)
        // The names a part of a line must say more than ([WebBoilerplate]).
        val subject = (listOf(name, artist) + performers).distinct()
        sent()
        return when (val fetched = net.fetch(request, ExaWebSearch::parse)) {
            is NetResult.Failure -> Lookup.Failed(UNREACHABLE)
            is NetResult.Success ->
                fetched.value?.let { response ->
                    Lookup.Found(
                        ExaWebSearch
                            .lines(response, clock.now(), subject, filter::fold) { result, snippet ->
                                filter.isAboutAlbum(name, artist, result.title, snippet, result.url, performers)
                            }.lines,
                    )
                } ?: Lookup.Failed(REFUSED)
        }
    }

    private companion object {
        const val UNREACHABLE = "Exa could not be reached"
        const val REFUSED = "Exa refused the search"
    }
}
