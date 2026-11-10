package app.sleevesight.sources.album

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.sources.musicbrainz.MbAlbum
import app.sleevesight.sources.musicbrainz.ReleaseGroups
import app.sleevesight.sources.web.AlbumWebSearch
import kotlinx.coroutines.CompletableDeferred
import kotlin.time.Instant

// The fakes an album's lookup is tested with (`AlbumSourceTest`, `ClassicalAlbumSourceTest`).

// The release groups by id, and the group of each release by the release's id ([failing]: that
// lookup fails); each asked counted.
internal class Groups(
    val albums: Map<String, MbAlbum>,
    val ofRelease: Map<String, String> = emptyMap(),
    val failing: Boolean = false,
) : ReleaseGroups {
    val asked = mutableListOf<String>()
    val releasesAsked = mutableListOf<String>()

    override suspend fun releaseGroup(mbid: String): Lookup<MbAlbum> {
        asked += mbid
        return albums[mbid]?.let { Lookup.Found(it) } ?: Lookup.NotFound
    }

    override suspend fun groupOfRelease(mbid: String): Lookup<String> {
        releasesAsked += mbid
        return when {
            failing -> Lookup.Failed("offline")
            else -> ofRelease[mbid]?.let { Lookup.Found(it) } ?: Lookup.NotFound
        }
    }
}

internal class Wikidata(
    val entities: Map<String, WdEntity>,
    val byGroup: Map<String, List<String>> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
) : WikidataLookup {
    override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> =
        Lookup.Found(ids.mapNotNull { entities[it] }.associateBy { it.id })

    override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> =
        Lookup.Found(ids.mapNotNull { id -> labels[id]?.let { id to it } }.toMap())

    override suspend fun claimValues(
        id: String,
        property: String,
    ): Lookup<List<String>> = error("not asked")

    override suspend fun itemsWithStatement(
        property: String,
        value: String,
    ): Lookup<List<String>> = Lookup.Found(byGroup[value].orEmpty())
}

// Wikipedia's answers: the pages a lookup by name finds (by title, with their items), and each article's lead.
internal class Wikipedia(
    val pages: Map<String, String> = emptyMap(),
    val leads: Map<String, String> = emptyMap(),
    val failing: Boolean = false,
) : NetClient {
    val sent = mutableListOf<NetRequest>()

    override suspend fun send(request: NetRequest): NetResult<NetResponse> {
        sent += request
        if (failing) return NetResult.Failure(NetError.Offline("io"))
        val title = request.query["titles"].orEmpty()
        val body =
            if (request.query["prop"] == "extracts") {
                val lead = leads[title].orEmpty().replace("\"", "\\\"")
                """{"query":{"pages":[{"title":"$title","extract":"$lead"}]}}"""
            } else {
                val found =
                    title.split('|').mapNotNull { asked ->
                        pages[asked]?.let { """{"title":"$asked","pageprops":{"wikibase_item":"$it"}}""" }
                    }
                """{"query":{"pages":[${found.joinToString(",")}]}}"""
            }
        return NetResult.Success(NetResponse(200, emptyMap(), body.encodeToByteArray()))
    }

    override fun close() = Unit
}

// A web search for an album, with the lines it finds, or its failure; each search counted, and,
// unless [sends] is off (an album it cannot check), its request sent.
internal class Web(
    val lines: List<String> = listOf("Sundown, Gordon Lightfoot's 1974 album, topped the Billboard 200."),
    var failing: Boolean = false,
    val sends: Boolean = true,
) : AlbumWebSearch {
    val asked = mutableListOf<Pair<String, String>>()

    // The performers each search was given (#1130).
    val performers = mutableListOf<List<String>>()

    // While set, a search waits for it: its answer on the way.
    var answering: CompletableDeferred<Unit>? = null

    override suspend fun search(
        album: String,
        artist: String,
        performers: List<String>,
        sent: () -> Unit,
    ): Lookup<List<EvidenceDraft>> {
        asked += album to artist
        this.performers += performers
        if (sends) sent()
        answering?.await()
        if (failing) return Lookup.Failed("offline")
        return Lookup.Found(
            lines.mapIndexed { i, text ->
                EvidenceDraft(
                    SourceId.WEB,
                    Scope.WEB,
                    text,
                    "https://example.com/$i",
                    Instant.fromEpochMilliseconds(1),
                )
            },
        )
    }
}
