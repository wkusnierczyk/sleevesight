package io.github.wkusnierczyk.sleevesight.sources.wikipedia

import io.github.wkusnierczyk.sleevesight.core.resolution.Lookup
import io.github.wkusnierczyk.sleevesight.net.NetClient
import io.github.wkusnierczyk.sleevesight.net.NetRequest
import io.github.wkusnierczyk.sleevesight.sources.array
import io.github.wkusnierczyk.sleevesight.sources.lookup
import io.github.wkusnierczyk.sleevesight.sources.obj
import io.github.wkusnierczyk.sleevesight.sources.parseObject
import io.github.wkusnierczyk.sleevesight.sources.string
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
 * The English Wikipedia action API (`https://en.wikipedia.org/w/api.php`), keyless, through the one
 * [NetClient], whose User-Agent names the app and a contact as Wikimedia's policy asks. Article
 * text is CC BY-SA 4.0: what the adapter takes from it is attributed (D1).
 */
class WikipediaClient(
    private val net: NetClient,
) {
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
        return net.lookup(NetRequest(API, query = query)) { response ->
            val json = parseObject(response.body).obj("query")
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
    suspend fun lead(title: String): Lookup<String?> {
        val query =
            mapOf(
                "action" to "query",
                "titles" to title,
                "prop" to "extracts",
                "exintro" to "1",
                "explaintext" to "1",
                "redirects" to "1",
                "format" to "json",
                "formatversion" to "2",
            )
        return net.lookup(NetRequest(API, query = query)) { response ->
            parseObject(response.body)
                .obj("query")
                .array("pages")
                .firstOrNull()
                ?.string("extract")
        }
    }

    companion object {
        const val API: String = "https://en.wikipedia.org/w/api.php"

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
 * The address of the English Wikipedia article [title]: spaces as underscores, and the title
 * percent-encoded as UTF-8 apart from the characters Wikipedia's own links leave as they are, so
 * a `?`, `#` or `%` in a title stays part of the path (`Why%3F_(Bronski_Beat_song)`).
 */
fun articleUrl(title: String): String {
    val path = StringBuilder()
    for (byte in title.replace(' ', '_').encodeToByteArray()) {
        val c = byte.toInt() and BYTE
        if (c < ASCII && c.toChar().let { it.isLetterOrDigit() || it in PATH_SAFE }) {
            path.append(c.toChar())
        } else {
            path.append('%').append(HEX[c shr NIBBLE]).append(HEX[c and LOW_NIBBLE])
        }
    }
    return "https://en.wikipedia.org/wiki/$path"
}

private const val PATH_SAFE = "-._~_()',:!*;@\$/"
private const val HEX = "0123456789ABCDEF"
private const val BYTE = 0xFF
private const val ASCII = 0x80
private const val NIBBLE = 4
private const val LOW_NIBBLE = 0x0F
