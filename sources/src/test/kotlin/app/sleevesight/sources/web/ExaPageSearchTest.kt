package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * *AI web search*'s Exa searches (#801): the query as given, the key only in a secret header,
 * MusicBrainz's domain left out of the request and of what comes back (D12: its pages carry its
 * tags), each page once with its highlight.
 */
class ExaPageSearchTest {
    private val body =
        """{"results": [
            {"url": "https://www.discogs.com/release/1", "title": "Grace", "highlights": ["Released 1994."]},
            {"url": "https://musicbrainz.org/recording/x", "title": "Grace", "highlights": ["Tags: shoegaze"]},
            {"url": "https://beta.musicbrainz.org/release/y", "title": "Grace", "highlights": ["Tags: shoegaze"]},
            {"url": "https://www.discogs.com/release/1", "title": "Grace", "highlights": ["Again."]},
            {"url": "https://example.org/none", "title": "No highlight", "highlights": []}
        ]}"""

    @Test
    fun theRequestLeavesOutMusicBrainzAndSoDoesWhatComesBack() =
        runTest {
            val net = ReplayNetClient.ok(body)
            val found = ExaPageSearch(net, Secret("exa-key")).search("\"Grace\" by Jeff Buckley, release date")
            assertEquals(
                WebSearchOutcome.Found(listOf(WebPage("https://www.discogs.com/release/1", "Grace", "Released 1994."))),
                found,
            )
            val sent = net.sent.single()
            assertEquals(mapOf(ExaWebSearch.KEY_HEADER to "exa-key"), sent.secrets.headers)
            val request = Json.parseToJsonElement(sent.body!!.text).jsonObject
            assertEquals("\"Grace\" by Jeff Buckley, release date", request["query"]!!.jsonPrimitive.content)
            assertEquals(
                listOf("musicbrainz.org"),
                request["excludeDomains"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            assertFalse("exa-key" in sent.body!!.text)
        }

    @Test
    fun aFailureIsFailed() =
        runTest {
            assertEquals(
                WebSearchOutcome.Failed,
                ExaPageSearch(ReplayNetClient.status(401, "{}"), Secret("k")).search("q"),
            )
        }
}
