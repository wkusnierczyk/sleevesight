package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.WebPage
import app.sleevesight.net.NetError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exa's search for an artist's pages (#912): the artist's name as a music artist, the key only in a
 * secret header, and the pages that name the artist kept as the artist's web lines.
 */
class ExaArtistSearchTest {
    // Exa's shape, with titles, addresses and highlights written for the test (`design/design.md`,
    // *Open on the terms*: no stored copy of Exa's results).
    private val body =
        """{"results": [
            {"url": "https://music.example.com/artist/shashwat-sachdev", "title": "Shashwat Sachdev | Music Guide",
             "highlights": ["Shashwat Sachdev writes film scores and songs, and produces his own records."]},
            {"url": "https://composer.example.org/about", "title": "About the composer",
             "highlights": ["Shashwat Sachdev trained in classical music before scoring his first film."]},
            {"url": "https://films.example.net/dhurandhar", "title": "Dhurandhar",
             "highlights": ["Dhurandhar is an action film with a soundtrack of reworked folk songs."]},
            {"url": "https://en.wikipedia.org/wiki/Shashwat_Sachdev", "title": "Shashwat Sachdev",
             "highlights": ["Shashwat Sachdev is a composer."]}
        ]}"""

    private fun search(net: ReplayNetClient) = ExaArtistSearch(net, Secret("exa-key"), JvmAccentFolder)

    @Test
    fun `the search asks for the artist as a music artist, and keeps the pages that name it`() =
        runTest {
            val net = ReplayNetClient.ok(body)
            var told = 0
            val found = search(net).search("Shashwat Sachdev") { told++ }
            assertEquals(1, told)
            val lines = (found as Lookup.Found).value
            // Not a page that does not name the artist, or Wikipedia's, a source of its own.
            assertEquals(
                listOf("https://music.example.com/artist/shashwat-sachdev", "https://composer.example.org/about"),
                lines.map { it.url },
            )
            assertTrue(lines.all { it.source == SourceId.WEB && it.scope == Scope.ARTIST })
            assertTrue(lines.all { it.text.length <= ExaWebSearch.LINE_CHARACTERS + 1 })
            val sent = net.sent.single()
            assertEquals(ExaWebSearch.ENDPOINT, sent.url)
            assertEquals(mapOf(ExaWebSearch.KEY_HEADER to "exa-key"), sent.secrets.headers)
            val request = Json.parseToJsonElement(sent.body!!.text).jsonObject
            assertEquals("\"Shashwat Sachdev\", music artist", request["query"]!!.jsonPrimitive.content)
            assertEquals("lite", request["type"]!!.jsonPrimitive.content)
            assertFalse("exa-key" in sent.body!!.text)
        }

    @Test
    fun `at most the web search's lines are kept`() =
        runTest {
            val results =
                (1..8).joinToString(",") { i ->
                    """{"url": "https://example.com/$i", "title": "Page $i",
                        "highlights": ["Shashwat Sachdev composed the score of film number $i."]}"""
                }
            val found = search(ReplayNetClient.ok("""{"results": [$results]}""")).search("Shashwat Sachdev")
            assertEquals(ExaWebSearch.MAX_LINES, (found as Lookup.Found).value.size)
        }

    @Test
    fun `an artist whose name cannot be checked is not searched`() =
        runTest {
            val net = ReplayNetClient.ok(body)
            var sent = 0
            assertEquals(Lookup.Found(emptyList()), search(net).search("!!!") { sent++ })
            assertTrue(net.sent.isEmpty())
            assertEquals(0, sent)
        }

    @Test
    fun `a refusal or a network failure is failed, its request still sent`() =
        runTest {
            var sent = 0
            assertIs<Lookup.Failed>(search(ReplayNetClient.status(401, "{}")).search("Shashwat Sachdev") { sent++ })
            assertIs<Lookup.Failed>(
                search(ReplayNetClient.failing(NetError.Offline("io"))).search("Shashwat Sachdev") { sent++ },
            )
            assertEquals(2, sent)
        }

    @Test
    fun `a page the AI service's search found gives its line by the same rules`() {
        val search = search(ReplayNetClient.ok(body))
        val artist = "Shashwat Sachdev"
        // A source the app reads itself, or a page that does not name the artist: no line.
        val wiki =
            WebPage(
                "https://en.wikipedia.org/wiki/Shashwat_Sachdev",
                "Shashwat Sachdev",
                "Shashwat Sachdev is a composer.",
            )
        assertNull(search.line(artist, wiki))
        assertNull(search.line(artist, WebPage("https://films.example.net/d", "Dhurandhar", "An action film.")))
        // A part that is only page boilerplate left out.
        val rights =
            WebPage("https://composer.example.org/a", null, "Shashwat Sachdev scored Uri. ... All rights reserved")
        assertEquals("Shashwat Sachdev scored Uri.", search.line(artist, rights))
        // Cut as a web line is, at a word boundary, the cut shown.
        val long =
            WebPage(
                "https://composer.example.org/b",
                null,
                "Shashwat Sachdev " + "scored another film ".repeat(30),
            )
        val cut = checkNotNull(search.line(artist, long))
        assertTrue(cut.length <= ExaWebSearch.LINE_CHARACTERS + 1 && cut.endsWith("…"))
    }
}
