package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.language.Languages
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.LanguageItems
import app.sleevesight.core.sources.LocalName
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SongWebQuery
import app.sleevesight.core.sources.WebPage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Exa's search for a song the other sources only list (#958): one call, in English or in the track's
 * language, about the recording. Synthetic results; no Exa call.
 */
class ExaSongSearchTest {
    private val now = Instant.parse("2026-11-06T12:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }
    private val normalizer = Normalizer(JvmAccentFolder)
    private val phil = SongWebQuery(normalizer.normalize("Phil's First Tear", "Kolonel Djafaar"))
    private val hindi = checkNotNull(Languages.ofCode("hi"))
    private val tumHiHo =
        SongWebQuery(normalizer.normalize("Tum Hi Ho", "Arijit Singh"), hindi, Resolution.NONE)

    private fun search(
        net: ReplayNetClient,
        items: LanguageItems = LanguageItems.NONE,
    ) = ExaSongSearch(net, Secret("k"), JvmAccentFolder, { items }, clock)

    private fun answer(vararg results: Triple<String, String, String>): String =
        results.joinToString(",", prefix = """{"results":[""", postfix = "]}") { (url, title, highlight) ->
            """{"url":"$url","title":"$title","highlights":["$highlight"]}"""
        }

    private fun body(net: ReplayNetClient) =
        Json
            .parseToJsonElement(
                net.sent
                    .single()
                    .body!!
                    .text,
            ).jsonObject

    @Test
    fun `in English, one call, with the title and first artist, review interview, highlights on its sound`() =
        runTest {
            val net = ReplayNetClient.ok(answer())
            val two = SongWebQuery(normalizer.normalize("Phil's First Tear", "Kolonel Djafaar, Someone Else"))
            search(net).search(two)
            val sent = body(net)
            assertEquals(
                "\"Phil's First Tear\" Kolonel Djafaar review interview",
                sent["query"]?.jsonPrimitive?.content,
            )
            val highlights =
                sent["contents"]
                    ?.jsonObject
                    ?.get("highlights")
                    ?.jsonObject
                    ?.get("query")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
            assertTrue("sound" in highlights && "Phil's First Tear" in highlights, highlights)
        }

    @Test
    fun `its results are web lines about the recording, others and the app's own sources left out`() =
        runTest {
            val net =
                ReplayNetClient.ok(
                    answer(
                        Triple(
                            "https://blog.example/a",
                            "Phil's First Tear by Kolonel Djafaar",
                            "Phil's First Tear by Kolonel Djafaar rides a heavy horn line.",
                        ),
                        Triple("https://blog.example/b", "Kolonel Djafaar", "A band from Antwerp."),
                        Triple(
                            "https://en.wikipedia.org/wiki/x",
                            "Phil's First Tear",
                            "Phil's First Tear by Kolonel Djafaar.",
                        ),
                    ),
                )
            var sent = 0
            val found =
                assertIs<Lookup.Found<List<EvidenceDraft>>>(
                    search(net).search(phil, { sent++ }),
                ).value
            assertEquals(1, sent)
            assertEquals(listOf("https://blog.example/a"), found.map { it.url })
            assertEquals(Scope.WEB, found.single().scope)
            assertNull(found.single().language)
        }

    @Test
    fun `in the language, that one call instead, its names, the language word, its lines marked`() =
        runTest {
            val net =
                ReplayNetClient.ok(
                    answer(
                        Triple(
                            "https://blog.example/hi",
                            "तुम ही हो",
                            "तुम ही हो अरिजीत सिंह का गाना है, धीमी धुन पर।",
                        ),
                    ),
                )
            val items =
                LanguageItems(
                    song = LocalName("Q1", "तुम ही हो", "तुम ही हो"),
                    artists = mapOf("Arijit Singh" to LocalName("Q2", "अरिजीत सिंह", null)),
                )
            val found =
                assertIs<Lookup.Found<List<EvidenceDraft>>>(
                    search(net, items).search(tumHiHo),
                ).value
            assertEquals(1, net.sent.size)
            assertEquals("\"तुम ही हो\" अरिजीत सिंह, ${hindi.song}", body(net)["query"]?.jsonPrimitive?.content)
            assertEquals("hi", found.single().language)
            assertFalse(found.single().scripted)
        }

    @Test
    fun `in the language with no resolution, the track's own names, Wikidata not asked`() =
        runTest {
            val net = ReplayNetClient.ok(answer())
            var asked = 0
            val source =
                ExaSongSearch(
                    net,
                    Secret("k"),
                    JvmAccentFolder,
                    {
                        asked++
                        LanguageItems.NONE
                    },
                    clock,
                )
            source.search(SongWebQuery(tumHiHo.track, hindi, null))
            assertEquals(0, asked)
            assertEquals("\"Tum Hi Ho\" Arijit Singh, ${hindi.song}", body(net)["query"]?.jsonPrimitive?.content)
        }

    @Test
    fun `an answer that is a failure or a refusal fails, and a gate that closes sends nothing`() =
        runTest {
            val refused = ReplayNetClient.status(401, "{}")
            assertIs<Lookup.Failed>(search(refused).search(phil))
            val net = ReplayNetClient.ok(answer())
            assertIs<Lookup.Failed>(search(net).search(phil, mayPay = { false }))
            assertTrue(net.sent.isEmpty())
        }

    @Test
    fun `a track that cannot be checked sends nothing, and finds nothing`() =
        runTest {
            val net = ReplayNetClient.ok(answer())
            var sent = 0
            val unchecked = SongWebQuery(normalizer.normalize("!!!", "???"))
            assertEquals(Lookup.Found(emptyList()), search(net).search(unchecked, { sent++ }))
            assertEquals(0, sent)
            assertTrue(net.sent.isEmpty())
        }

    @Test
    fun `a page the AI service found gives a line by the same rules, marked in the language`() =
        runTest {
            val lines = search(ReplayNetClient.ok(answer())).pageLines(phil)
            val about =
                WebPage(
                    "https://blog.example/a",
                    "Phil's First Tear by Kolonel Djafaar",
                    "Phil's First Tear by Kolonel Djafaar rides a heavy horn line.",
                )
            assertEquals(about.text, assertNotNull(lines(about, now)).text)
            assertNull(lines(WebPage("https://blog.example/b", "Other", "Something else."), now))
            assertNull(lines(about.copy(url = "https://en.wikipedia.org/wiki/x"), now))
            val inHindi =
                search(ReplayNetClient.ok(answer()), LanguageItems(song = LocalName("Q1", "तुम ही हो", "तुम ही हो")))
                    .pageLines(tumHiHo)
            val page = WebPage("https://blog.example/hi", "तुम ही हो", "तुम ही हो Arijit Singh का गाना है।")
            assertEquals("hi", inHindi(page, now)?.language)
        }
}
