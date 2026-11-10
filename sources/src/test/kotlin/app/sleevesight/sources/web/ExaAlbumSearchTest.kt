package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.net.NetError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exa's search for an album's pages (#885): the album's title and its artist as an album, the key
 * only in a secret header, and the pages about the album kept as web lines, as the track's are.
 */
class ExaAlbumSearchTest {
    private val body =
        """{"results": [
            {"url": "https://example.com/sundown", "title": "Sundown - Gordon Lightfoot",
             "highlights": ["Sundown is Gordon Lightfoot's 1974 album, his only number one."]},
            {"url": "https://example.com/rank", "title": "Sundown",
             "highlights": ["Sundown is the debut album by Rank and File."]},
            {"url": "https://example.com/artist", "title": "Gordon Lightfoot",
             "highlights": ["His albums include Sundown and Summertime Dream."]},
            {"url": "https://en.wikipedia.org/wiki/Sundown", "title": "Sundown (Gordon Lightfoot album)",
             "highlights": ["Sundown is the tenth album by Gordon Lightfoot."]}
        ]}"""

    private fun search(net: ReplayNetClient) = ExaAlbumSearch(net, Secret("exa-key"), JvmAccentFolder)

    @Test
    fun `the search asks for the album by its artist, and keeps the pages about it`() =
        runTest {
            val net = ReplayNetClient.ok(body)
            var told = 0
            val found = search(net).search("Sundown", "Gordon Lightfoot") { told++ }
            assertEquals(1, told)
            val lines = (found as Lookup.Found).value
            // Not another artist's album of the name, a page titled after the artist, or Wikipedia's.
            assertEquals(listOf("https://example.com/sundown"), lines.map { it.url })
            assertEquals("Sundown is Gordon Lightfoot's 1974 album, his only number one.", lines.single().text)
            assertTrue(lines.all { it.source == SourceId.WEB && it.scope == Scope.WEB })
            val sent = net.sent.single()
            assertEquals(ExaWebSearch.ENDPOINT, sent.url)
            assertEquals(mapOf(ExaWebSearch.KEY_HEADER to "exa-key"), sent.secrets.headers)
            val request = Json.parseToJsonElement(sent.body!!.text).jsonObject
            assertEquals("\"Sundown\" by Gordon Lightfoot, album", request["query"]!!.jsonPrimitive.content)
            assertEquals("lite", request["type"]!!.jsonPrimitive.content)
            assertFalse("exa-key" in sent.body!!.text)
        }

    @Test
    fun `an album's edition is left out of the query and of what a page must name`() =
        runTest {
            val net = ReplayNetClient.ok(body)
            val found = search(net).search("Sundown (Remastered 2024)", "Gordon Lightfoot")
            assertEquals(listOf("https://example.com/sundown"), (found as Lookup.Found).value.map { it.url })
            val request =
                Json
                    .parseToJsonElement(
                        net.sent
                            .single()
                            .body!!
                            .text,
                    ).jsonObject
            assertEquals("\"Sundown\" by Gordon Lightfoot, album", request["query"]!!.jsonPrimitive.content)
            // A live album is another release: its name stays.
            val live = ReplayNetClient.ok(body)
            assertEquals(Lookup.Found(emptyList()), search(live).search("Sundown (Live)", "Gordon Lightfoot"))
            val sent =
                Json
                    .parseToJsonElement(
                        live.sent
                            .single()
                            .body!!
                            .text,
                    ).jsonObject
            assertEquals("\"Sundown (Live)\" by Gordon Lightfoot, album", sent["query"]!!.jsonPrimitive.content)
        }

    // A classical album (#1130): Buniatishvili's 2024 recording, and Shelley's of the same title, 1991.
    private val concertos = "Mozart: Piano Concertos Nos. 20 & 23"

    private val performers = listOf("Khatia Buniatishvili", "Academy of St. Martin in the Fields")

    private val classicalBody =
        """{"results": [
            {"url": "https://example.com/sony", "title": "$concertos - Sony Classical",
             "highlights": ["$concertos, Khatia Buniatishvili's 2024 album with the Academy."]},
            {"url": "https://example.com/chandos", "title": "$concertos - Chandos",
             "highlights": ["$concertos: Howard Shelley and the London Mozart Players play Wolfgang Amadeus Mozart, 1991."]},
            {"url": "https://example.com/composer", "title": "$concertos",
             "highlights": ["Wolfgang Amadeus Mozart wrote the concertos of $concertos in 1785."]}
        ]}"""

    @Test
    fun `a classical album's search names every credited artist, and keeps only pages naming a performer`() =
        runTest {
            val net = ReplayNetClient.ok(classicalBody)
            val found = search(net).search(concertos, "Wolfgang Amadeus Mozart", performers)
            // Not Shelley's recording of the same title, nor a page naming the composer alone.
            assertEquals(listOf("https://example.com/sony"), (found as Lookup.Found).value.map { it.url })
            val request =
                Json
                    .parseToJsonElement(
                        net.sent
                            .single()
                            .body!!
                            .text,
                    ).jsonObject
            assertEquals(
                "\"$concertos\" by Wolfgang Amadeus Mozart, Khatia Buniatishvili and Academy of St. Martin in the " +
                    "Fields, album",
                request["query"]!!.jsonPrimitive.content,
            )
        }

    @Test
    fun `an album without performers keeps a page naming its artist, as before`() =
        runTest {
            // The same pages, the album by its first artist alone, as every album was searched before
            // #1130: those naming the album and Mozart, Shelley's recording among them.
            val found = search(ReplayNetClient.ok(classicalBody)).search(concertos, "Wolfgang Amadeus Mozart")
            assertEquals(
                listOf("https://example.com/chandos", "https://example.com/composer"),
                (found as Lookup.Found).value.map { it.url },
            )
        }

    @Test
    fun `an album that cannot be checked is not searched`() =
        runTest {
            val net = ReplayNetClient.ok(body)
            var sent = 0
            assertEquals(Lookup.Found(emptyList()), search(net).search("!!!", "Gordon Lightfoot") { sent++ })
            assertEquals(Lookup.Found(emptyList()), search(net).search("Sundown", " ") { sent++ })
            assertTrue(net.sent.isEmpty())
            assertEquals(0, sent)
        }

    @Test
    fun `a refusal or a network failure is failed, its request still sent`() =
        runTest {
            var sent = 0
            val refused = search(ReplayNetClient.status(401, "{}")).search("Sundown", "Gordon Lightfoot") { sent++ }
            assertIs<Lookup.Failed>(refused)
            assertIs<Lookup.Failed>(
                search(
                    ReplayNetClient.failing(NetError.Offline("io")),
                ).search("Sundown", "Gordon Lightfoot") { sent++ },
            )
            // Each was sent, and is counted, whatever came back.
            assertEquals(2, sent)
        }
}
