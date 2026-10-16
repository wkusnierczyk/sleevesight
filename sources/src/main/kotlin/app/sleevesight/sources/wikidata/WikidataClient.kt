package app.sleevesight.sources.wikidata

import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.WdEntity
import app.sleevesight.core.resolution.WikidataLookup
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetRequest
import app.sleevesight.sources.array
import app.sleevesight.sources.int
import app.sleevesight.sources.lookup
import app.sleevesight.sources.obj
import app.sleevesight.sources.parseObject
import app.sleevesight.sources.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Wikidata's action API (`https://www.wikidata.org/w/api.php`), keyless, through the one
 * [NetClient], whose User-Agent names the app and a contact as Wikimedia's policy asks. Wikidata
 * is CC0.
 */
class WikidataClient(
    private val net: NetClient,
) : WikidataLookup {
    override suspend fun entities(ids: List<String>): Lookup<Map<String, WdEntity>> =
        getEntities(ids, "claims|sitelinks|labels") { id, json ->
            WdEntity(
                id = id,
                label = label(json),
                enwiki = json.obj("sitelinks").obj("enwiki").string("title"),
                claims =
                    json.obj("claims")?.mapValues { (_, claims) -> values(claims as? JsonArray) }.orEmpty(),
            )
        }

    override suspend fun labels(ids: List<String>): Lookup<Map<String, String>> =
        when (val found = getEntities(ids, "labels") { _, json -> label(json) }) {
            is Lookup.Found -> Lookup.Found(found.value.mapNotNull { (id, label) -> label?.let { id to it } }.toMap())
            is Lookup.NotFound -> found
            is Lookup.Failed -> found
        }

    override suspend fun claimValues(
        id: String,
        property: String,
    ): Lookup<List<String>> {
        val request =
            NetRequest(
                API,
                query =
                    mapOf(
                        "action" to "wbgetclaims",
                        "entity" to id,
                        "property" to property,
                        "format" to "json",
                    ),
            )
        return net.lookup(request) { response ->
            values(parseObject(response.body).obj("claims")?.get(property) as? JsonArray)
        }
    }

    override suspend fun itemsWithStatement(
        property: String,
        value: String,
    ): Lookup<List<String>> {
        val query =
            mapOf(
                "action" to "query",
                "list" to "search",
                "srsearch" to "haswbstatement:$property=$value",
                "srnamespace" to "0",
                "format" to "json",
            )
        return net.lookup(NetRequest(API, query = query)) { response ->
            parseObject(response.body).obj("query").array("search").mapNotNull { it.string("title") }
        }
    }

    // `wbgetentities` takes at most 50 ids; the check asks for a few at a time.
    private suspend fun <T> getEntities(
        ids: List<String>,
        props: String,
        read: (String, JsonElement) -> T,
    ): Lookup<Map<String, T>> {
        val query =
            mapOf(
                "action" to "wbgetentities",
                "ids" to ids.distinct().take(MAX_IDS).joinToString("|"),
                "props" to props,
                "languages" to "en",
                "sitefilter" to "enwiki",
                "format" to "json",
            )
        return net.lookup(NetRequest(API, query = query)) { response ->
            parseObject(response.body)
                .obj("entities")
                .orEmpty()
                .filterValues { (it as? JsonObject)?.containsKey("missing") == false }
                .mapValues { (id, json) -> read(id, json) }
        }
    }

    companion object {
        const val API: String = "https://www.wikidata.org/w/api.php"

        private const val MAX_IDS = 50

        private const val YEAR = 9
        private const val MONTH = 10

        // A time keeps only the precision Wikidata gives it: a year stored as `+1964-01-01` is `+1964-00-00`.
        internal fun atPrecision(
            time: String,
            precision: Int?,
        ): String {
            // A decade or a century is no date the evidence states.
            if (precision != null && precision < YEAR) return ""
            val (date, rest) = time.substringBefore('T') to time.substringAfter('T', "")
            val parts = date.split('-').toMutableList()
            val sign = if (date.startsWith("-")) 1 else 0
            val kept =
                when (precision) {
                    YEAR -> sign + 1
                    MONTH -> sign + 2
                    else -> parts.size
                }
            for (i in kept until parts.size) parts[i] = "00"
            return parts.joinToString("-") + if (rest.isEmpty()) "" else "T$rest"
        }

        private fun label(json: JsonElement): String? = json.obj("labels").obj("en").string("value")

        // Each claim's value, deprecated ones left out: an item's id, a string, or a time.
        private fun values(claims: JsonArray?): List<String> =
            claims
                .orEmpty()
                .filter { it.string("rank") != "deprecated" }
                .mapNotNull { claim ->
                    when (val value = claim.obj("mainsnak").obj("datavalue")?.get("value")) {
                        is JsonPrimitive -> value.contentOrNull
                        is JsonObject ->
                            value.string("id")
                                ?: value.string("time")?.let { atPrecision(it, value.int("precision")) }
                        else -> null
                    }
                }
    }
}
