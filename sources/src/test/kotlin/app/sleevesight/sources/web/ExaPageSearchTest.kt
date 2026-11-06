package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearchOutcome
import app.sleevesight.fixtures.Fixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * *AI web search*'s Exa searches (#801): the query as given, the key only in a secret header,
 * MusicBrainz's domain left out of the request and of what comes back (D12: its pages carry its
 * tags), each page once with its highlight; and each page's passage without the parts that are only
 * page boilerplate (#929), as the source's lines are (#905, [WebBoilerplateTest]'s synthetic pages:
 * Exa's returned text is not stored).
 */
class ExaPageSearchTest {
    private val sundown = listOf("Sundown", "Gordon Lightfoot")

    // A search of [body] for [subject], with the test key.
    private suspend fun search(
        body: String,
        subject: List<String>? = sundown,
    ): WebSearchOutcome =
        ExaPageSearch(ReplayNetClient.ok(body), Secret("exa-key"), JvmAccentFolder).search("q", subject)

    // The pages found in [body] for [subject].
    private suspend fun pagesIn(
        body: String,
        subject: List<String>? = sundown,
    ): List<WebPage> = assertIs<WebSearchOutcome.Found>(search(body, subject)).pages

    // A response of one page at [url] with [highlights].
    private fun one(
        url: String,
        vararg highlights: String,
    ): String =
        ExaWebSearch.JSON.encodeToString(ExaSearchResponse(listOf(ExaResult(url, "Sundown", highlights.toList()))))

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
            val found =
                ExaPageSearch(net, Secret("exa-key"), JvmAccentFolder)
                    .search("\"Grace\" by Jeff Buckley, release date", listOf("Grace", "Jeff Buckley"))
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
                ExaPageSearch(ReplayNetClient.status(401, "{}"), Secret("k"), JvmAccentFolder).search("q", sundown),
            )
        }

    @Test
    fun `pages lose their parts that are only boilerplate, as the source's lines do`() =
        runTest {
            val response = ExaWebSearch.JSON.decodeFromString<ExaSearchResponse>(Fixtures.text("http/$BOILERPLATE"))
            val pages = pagesIn(Fixtures.text("http/$BOILERPLATE"))
            val filter = RecordingFilter(JvmAccentFolder)
            // Every page kept has the source's line for it, but longer: none of these is past 300.
            val lines =
                response.results
                    .associate { it.url to ExaWebSearch.lineOf(it, sundown, filter::fold) }
                    .filterValues { it.isNotEmpty() }
            assertEquals(lines.keys.toList(), pages.map { it.url })
            assertEquals(lines.values.toList(), pages.map { it.text })
            // The video page of a title and footer, and the chord sheet's title alone, give no page.
            assertFalse(pages.any { it.url == "https://www.youtube.com/watch?v=sundown-official" })
            assertFalse(pages.any { it.url == "https://tabs.ultimate-guitar.com/tab/fixture/sundown-chords" })
            assertEquals(
                "### credits from Sundown, released January 1, 1974",
                pages.single { it.url == "https://sundown-fixture.bandcamp.com/track/sundown" }.text,
            )
        }

    @Test
    fun `a part goes whole or stays whole`() =
        runTest {
            val stats = "- Likes: 98K ## Description Gordon Lightfoot recorded Sundown in 1974."
            val page = pagesIn(one("https://www.youtube.com/watch?v=x", "Gordon Lightfoot - Sundown - YouTube", stats))
            assertEquals(listOf(stats), page.map { it.text })
        }

    @Test
    fun `the cut is made after the boilerplate goes, so its room goes to the page's own text`() =
        runTest {
            val footer =
                "About Press Copyright Contact us Creators Advertise Developers Terms Privacy Policy & Safety " +
                    "How YouTube works Test new features"
            val prose = (1..40).joinToString(" ") { "Lightfoot played it live in $it towns." }
            val text = pagesIn(one("https://www.youtube.com/watch?v=x", footer, prose)).single().text
            assertTrue(text.startsWith("Lightfoot played it live in 1 towns."), text)
            assertTrue(text.endsWith("…") && text.length <= ExaPageSearch.PAGE_CHARACTERS + 1, text)
        }

    @Test
    fun `the subject's names are what a part must say more than`() =
        runTest {
            val title = "Gordon Lightfoot - Sundown (Official Audio) - YouTube"
            val url = "https://www.youtube.com/watch?v=x"
            assertEquals(emptyList(), pagesIn(one(url, title)))
            // For another subject, the names are words the part says more in: kept as it came.
            assertEquals(listOf(title), pagesIn(one(url, title), listOf("Grace", "Jeff Buckley")).map { it.text })
        }

    @Test
    fun `a search whose pages are all boilerplate found nothing, not a failure`() =
        runTest {
            val body = one("https://www.youtube.com/watch?v=x", "Gordon Lightfoot - Sundown (Official Audio) - YouTube")
            assertEquals(WebSearchOutcome.Found(emptyList()), search(body))
        }

    @Test
    fun `with no names, a lyrics page whose passage is only its title stays, for its address`() =
        runTest {
            val genius = "https://genius.com/Gordon-lightfoot-sundown-lyrics"
            val title = "Gordon Lightfoot – Sundown Lyrics | Genius Lyrics"
            assertEquals(emptyList(), pagesIn(one(genius, title)))
            assertEquals(listOf(genius), pagesIn(one(genius, title), subject = emptyList()).map { it.url })
        }

    @Test
    fun `with null for names, a page is kept as it came, as the lyrics pages are`() =
        runTest {
            val genius = "https://genius.com/Gordon-lightfoot-sundown-lyrics"
            assertEquals(emptyList(), pagesIn(one(genius, "Genius Lyrics"), subject = emptyList()))
            assertEquals(listOf("Genius Lyrics"), pagesIn(one(genius, "Genius Lyrics"), subject = null).map { it.text })
        }

    @Test
    fun `with no names, an artist's page named only in a part of its own still gives the artist's line`() =
        runTest {
            // The artist's insight's searches send none: `ArtistWebSearch.line` judges, then cleans.
            val body =
                ExaWebSearch.JSON.encodeToString(
                    ExaSearchResponse(
                        listOf(
                            ExaResult(
                                "https://folk-archive.example/1938",
                                "Folk archive",
                                listOf("Gordon Lightfoot", "Born in Orillia in 1938."),
                            ),
                        ),
                    ),
                )
            val page = pagesIn(body, subject = emptyList()).single()
            val artist = ExaArtistSearch(ReplayNetClient.ok("{}"), Secret("exa-key"), JvmAccentFolder)
            assertEquals("Born in Orillia in 1938.", artist.line("Gordon Lightfoot", page))
        }

    private companion object {
        const val BOILERPLATE = "web/exa-01-sundown-boilerplate.response.json"
    }
}
