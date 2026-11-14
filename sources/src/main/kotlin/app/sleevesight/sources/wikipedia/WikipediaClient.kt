package app.sleevesight.sources.wikipedia

import app.sleevesight.core.resolution.Lookup
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetRequest
import app.sleevesight.sources.array
import app.sleevesight.sources.lookup
import app.sleevesight.sources.obj
import app.sleevesight.sources.parseWikimedia
import app.sleevesight.sources.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * An English Wikipedia article as the lookup by name finds it.
 *
 * @property item its Wikidata item (`wikibase_item`), if it has one.
 */
data class WpPage(
    val title: String,
    val item: String?,
    val disambiguation: Boolean,
)

/**
 * An article's lead as [WikipediaClient.songLead] reads it: [text], the plain text of its lead section,
 * and [opening], the wikitext of the same section, the infobox's (#1273); either null when missing.
 */
data class WpLead(
    val text: String?,
    val opening: String?,
)

/**
 * A Wikipedia's action API, the English one's (`https://en.wikipedia.org/w/api.php`) unless [wiki]
 * names another's subdomain (`hi`, for the search in the track's language, #902), keyless, through
 * the one [NetClient], whose User-Agent names the app and a contact as Wikimedia's policy asks.
 * Article text is CC BY-SA 4.0: what the adapter takes from it is attributed (D1).
 */
class WikipediaClient(
    private val net: NetClient,
    wiki: String = ENGLISH,
) {
    private val api = apiOf(wiki)

    /**
     * The articles [titles] lead to, by each title asked, following Wikipedia's normalization and
     * redirects; a title with no article maps to null. One request for at most 50 titles.
     */
    suspend fun pages(titles: List<String>): Lookup<Map<String, WpPage?>> {
        val asked = titles.distinct().take(MAX_TITLES)
        val query =
            mapOf(
                "action" to "query",
                "titles" to asked.joinToString("|"),
                "redirects" to "1",
                "prop" to "pageprops",
                "ppprop" to "wikibase_item|disambiguation",
                "format" to "json",
                "formatversion" to "2",
            )
        return net.lookup(NetRequest(api, query = query)) { response ->
            val json = parseWikimedia(response.body).obj("query")
            val moves =
                (json.array("normalized") + json.array("redirects")).associate {
                    it.required("from") to
                        it.required("to")
                }
            val pages =
                json
                    .array("pages")
                    .filter { it.isPresent() }
                    .associate { page ->
                        val title = page.required("title")
                        val props = page.obj("pageprops")
                        title to
                            WpPage(title, props.string("wikibase_item"), props?.containsKey("disambiguation") == true)
                    }
            asked.associateWith { title -> pages[follow(title, moves)] }
        }
    }

    /** The plain text of [title]'s lead section, or null when there is no such article. */
    suspend fun lead(title: String): Lookup<String?> =
        net.lookup(NetRequest(api, query = leadQuery(title, "extracts"))) { response ->
            parseWikimedia(response.body)
                .obj("query")
                .array("pages")
                .firstOrNull()
                ?.string("extract")
        }

    /**
     * A song's article [title]: its lead's plain text, as [lead] gives it, and in the same request the
     * wikitext of its lead section (`rvsection=0`), which holds its infobox (#1273); null when there
     * is no such article.
     */
    suspend fun songLead(title: String): Lookup<WpLead?> {
        val query =
            leadQuery(title, "extracts|revisions") +
                mapOf("rvprop" to "content", "rvslots" to "main", "rvsection" to "0")
        return net.lookup(NetRequest(api, query = query)) { response ->
            parseWikimedia(response.body)
                .obj("query")
                .array("pages")
                .firstOrNull()
                ?.takeIf { it.isPresent() }
                ?.let { page ->
                    val opening =
                        page
                            .array("revisions")
                            .firstOrNull()
                            .obj("slots")
                            .obj("main")
                            .string("content")
                    WpLead(page.string("extract"), opening)
                }
        }
    }

    // The query of [title]'s lead, with [prop] (its extract, and perhaps its wikitext).
    private fun leadQuery(
        title: String,
        prop: String,
    ): Map<String, String> =
        mapOf(
            "action" to "query",
            "titles" to title,
            "prop" to prop,
            "exintro" to "1",
            "explaintext" to "1",
            "redirects" to "1",
            "format" to "json",
            "formatversion" to "2",
        )

    companion object {
        const val API: String = "https://en.wikipedia.org/w/api.php"

        /** English Wikipedia's subdomain, the default. */
        const val ENGLISH: String = "en"

        /** The action API of the Wikipedia at subdomain [wiki]: [API] for `en`. */
        fun apiOf(wiki: String): String = "https://$wiki.wikipedia.org/w/api.php"

        private const val MAX_TITLES = 50

        // Follows normalization, then redirects, at most a few steps (a redirect can follow a normalization).
        private fun follow(
            title: String,
            moves: Map<String, String>,
        ): String {
            var current = title
            repeat(MAX_MOVES) { current = moves[current] ?: current }
            return current
        }

        private const val MAX_MOVES = 3

        // `formatversion=2` marks a missing page with `"missing": true`.
        private fun JsonElement.isPresent(): Boolean = (this as? JsonObject)?.containsKey("missing") == false

        private fun JsonElement.required(key: String): String = string(key) ?: error("missing field $key")
    }
}

/**
 * The address of the article [title] on the Wikipedia at subdomain [wiki], English's by default:
 * spaces as underscores, and the title percent-encoded as UTF-8 apart from the characters
 * Wikipedia's own links leave as they are, so a `?`, `#` or `%` in a title stays part of the path
 * (`Why%3F_(Bronski_Beat_song)`), and a title in another script is all percent-encoded.
 */
fun articleUrl(
    title: String,
    wiki: String = WikipediaClient.ENGLISH,
): String {
    val path = StringBuilder()
    for (byte in title.replace(' ', '_').encodeToByteArray()) {
        val c = byte.toInt() and BYTE
        if (c < ASCII && c.toChar().let { it.isLetterOrDigit() || it in PATH_SAFE }) {
            path.append(c.toChar())
        } else {
            path.append('%').append(HEX[c shr NIBBLE]).append(HEX[c and LOW_NIBBLE])
        }
    }
    return "https://$wiki.wikipedia.org/wiki/$path"
}

private const val PATH_SAFE = "-._~_()',:!*;@\$/"
private const val HEX = "0123456789ABCDEF"
private const val BYTE = 0xFF
private const val ASCII = 0x80
private const val NIBBLE = 4
private const val LOW_NIBBLE = 0x0F
