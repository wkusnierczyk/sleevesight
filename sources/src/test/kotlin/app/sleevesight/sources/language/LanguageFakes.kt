package app.sleevesight.sources.language

import app.sleevesight.core.language.Language
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.Wd
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult

/**
 * A [NetClient] that answers each request with the body [route] gives it, status 200, or as offline
 * when it gives none; it keeps the requests sent. No network.
 */
internal class RoutedNet(
    private val route: (NetRequest) -> String?,
) : NetClient {
    val sent = mutableListOf<NetRequest>()

    override suspend fun send(request: NetRequest): NetResult<NetResponse> {
        sent += request
        val body = route(request) ?: return NetResult.Failure(NetError.Offline("not routed"))
        return NetResult.Success(NetResponse(200, emptyMap(), body.encodeToByteArray()))
    }

    override fun close() = Unit
}

/** A Wikidata of [items], each with its claims, labels by language and sitelinks by site; MusicBrainz ids by item. */
internal class FakeWikidata(
    private val items: Map<String, WdEntity>,
    private val byMbid: Map<String, String> = emptyMap(),
) : WikidataLookup {
    val asked = mutableListOf<List<String>>()
    val askedIn = mutableListOf<Pair<List<String>, String>>()

    override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> {
        asked += ids
        return Lookup.Found(
            ids
                .mapNotNull { id ->
                    items[id]?.let {
                        id to
                            it.copy(labels = emptyMap(), sitelinks = emptyMap())
                    }
                }.toMap(),
        )
    }

    override suspend fun entities(
        ids: List<String>,
        language: Language,
    ): Lookup<Map<String, WdEntity>> {
        askedIn += ids to language.code
        return Lookup.Found(ids.mapNotNull { id -> items[id]?.let { id to it } }.toMap())
    }

    override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> =
        Lookup.Found(ids.mapNotNull { id -> items[id]?.label?.let { id to it } }.toMap())

    override suspend fun claimValues(
        id: String,
        property: String,
    ): Lookup<List<String>> = Lookup.Found(items[id]?.values(property).orEmpty())

    override suspend fun itemsWithStatement(
        property: String,
        value: String,
    ): Lookup<List<String>> =
        Lookup.Found(
            when (property) {
                Wd.MUSICBRAINZ_ARTIST -> listOfNotNull(byMbid[value])
                else -> items.values.filter { value in it.values(property) }.map { it.id }
            },
        )
}
