package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.fixtures.BelowSoundtrack
import app.sleevesight.fixtures.Fixtures
import app.sleevesight.fixtures.RegressionCase
import app.sleevesight.net.NetError
import app.sleevesight.net.NetMethod
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class ExaWebSearchTest {
    private val key = "test-exa-key-0123456789"
    private val now = Instant.parse("2026-10-14T00:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }
    private val normalizer = Normalizer(JvmAccentFolder)

    private fun track(n: Int): NormalizedTrack =
        BelowSoundtrack.track(n).let { normalizer.normalize(it.title, it.artist) }

    private fun adapter(net: ReplayNetClient) = ExaWebSearch(net, Secret(key), JvmAccentFolder, clock)

    private suspend fun lookUp(
        n: Int,
        recording: String,
    ): Pair<SourceResult, ReplayNetClient> {
        val net = ReplayNetClient.recorded("web/exa-$recording.response.json")
        val result = adapter(net).lookup(track(n), CompletableDeferred(Resolution.NONE))
        return result to net
    }

    /** The fixture's result for [url]: its highlights joined as the adapter joins them. */
    private fun recordedSnippet(
        recording: String,
        url: String,
    ): String {
        val results =
            Json
                .parseToJsonElement(Fixtures.text("http/web/exa-$recording.response.json"))
                .jsonObject["results"]!!
                .jsonArray
        val result = results.first { it.jsonObject["url"]!!.jsonPrimitive.content == url }.jsonObject
        return result["highlights"]!!
            .jsonArray
            .map {
                it.jsonPrimitive.content
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }.filter { it.isNotEmpty() }
            .joinToString(" ... ")
    }

    private fun assertLines(
        recording: String,
        expectedUrls: List<String>,
        result: SourceResult,
    ) {
        assertEquals(expectedUrls, result.evidence.map { it.url })
        result.evidence.forEach { line ->
            assertEquals(SourceId.WEB, line.source)
            assertEquals(Scope.WEB, line.scope)
            assertEquals(now, line.retrievedAt)
            assertEquals(recordedSnippet(recording, line.url!!), line.text)
        }
    }

    @Test
    fun `sends the recorded request, with the key only in a secret header`() =
        runTest {
            for ((n, recording) in RECORDINGS) {
                val (_, net) = lookUp(n, recording)
                val request = net.sent.single()
                assertEquals("https://api.exa.ai/search", request.url)
                assertEquals(NetMethod.POST, request.method)
                assertEquals(mapOf("x-api-key" to key), request.secrets.headers)
                assertTrue(request.secrets.query.isEmpty())
                assertTrue(request.headers.isEmpty() && request.query.isEmpty())
                assertFalse(request.sensitiveUrl)
                val body = request.body!!
                assertEquals("application/json", body.contentType)
                assertFalse(key in body.text || key in request.url, "the key leaked into the request")
                assertEquals(
                    Json.parseToJsonElement(Fixtures.text("http/web/exa-$recording.request.json")),
                    Json.parseToJsonElement(body.text),
                    "track $n",
                )
            }
        }

    @Test
    fun `rich track keeps the first five results that name the song and the artist`() =
        runTest {
            val (result, _) = lookUp(1, "01-sundown")
            assertEquals(Outcome.SongArticle, result.outcome)
            assertLines(
                "01-sundown",
                listOf(
                    "https://songnotes.example/gordon-lightfoot/sundown",
                    "https://videos.example/watch?v=sundown74",
                    "https://streaming.example/track/sundown-lightfoot",
                    "https://charts.example/sundown",
                    "https://reviews.example/songs/sundown",
                ),
                result,
            )
            assertEquals(ExaWebSearch.MAX_LINES, result.evidence.size)
            // Whitespace collapsed; two highlights joined.
            assertEquals(
                "Gordon Lightfoot's Sundown came out as a single in the spring of 1974, " +
                    "the title track of the album of the same name.",
                result.evidence[0].text,
            )
            assertEquals(
                "Official audio for Sundown, from the 1974 album. ... Gordon Lightfoot's only US number one single.",
                result.evidence[1].text,
            )
        }

    @Test
    fun `same title by other artists is dropped`() =
        runTest {
            // RegressionCase.SAME_NAME_ARTIST: here the other "Bad Energy"s are Juice WRLD's and Safaree's.
            val (result, _) = lookUp(RegressionCase.SAME_NAME_ARTIST.tracks.first(), "12-bad-energy")
            assertEquals(Outcome.SongArticle, result.outcome)
            assertLines(
                "12-bad-energy",
                listOf(
                    "https://charts.example/bad-energy-reem",
                    "https://songtexts.example/reem/bad-energy",
                ),
                result,
            )
        }

    @Test
    fun `thin tracks keep only the listings that name the recording`() =
        runTest {
            // RegressionCase.NOTHING_DOCUMENTED: 23 and 30. The catalogue entry spells the artist with `&`.
            val (dory, _) = lookUp(23, "23-fishin-in-a-dory")
            assertEquals(Outcome.SongArticle, dory.outcome)
            assertLines(
                "23-fishin-in-a-dory",
                listOf(
                    "https://whatsong.example/song/fishin-in-a-dory",
                    "https://library.example/catalog/the-newfoundland-i-love",
                    "https://soundtrack.example/below",
                ),
                dory,
            )

            val (veil, _) = lookUp(30, "30-the-veil-between-worlds")
            assertEquals(Outcome.SongArticle, veil.outcome)
            assertLines(
                "30-the-veil-between-worlds",
                listOf(
                    "https://whatsong.example/song/the-veil-between-worlds",
                    "https://reviews.example/recording/the-veil-between-worlds",
                ),
                veil,
            )
        }

    @Test
    fun `a title shared by famous songs gives a rejection, not their facts`() =
        runTest {
            // RegressionCase.NOTHING_DOCUMENTED: 77, where every result is Alphaville's or Rod Stewart's.
            val (result, _) = lookUp(77, "77-forever-young")
            assertEquals(Outcome.Rejected(ExaWebSearch.REJECTED), result.outcome)
            assertTrue(result.evidence.isEmpty())
        }

    @Test
    fun `the work a theme is from is required`() =
        runTest {
            // Track 34, Theme - From "Coronation Street": not London Music Works' Doctor Who or EastEnders theme.
            val (result, _) = lookUp(34, "34-theme")
            assertLines(
                "34-theme",
                listOf(
                    "https://streaming.example/track/coronation-street-theme-lmw",
                    "https://tvthemes.example/coronation-street",
                ),
                result,
            )
        }

    @Test
    fun `a page titled after another song by the artist is dropped`() =
        runTest {
            // RegressionCase.COVERS, 45: Yanjoco's "Caravan" page lists the song among the artist's others.
            val (result, _) = lookUp(45, "45-i-will-follow-you-into-the-dark")
            assertLines(
                "45-i-will-follow-you-into-the-dark",
                listOf(
                    "https://streaming.example/track/i-will-follow-you-into-the-dark-yanjoco",
                    "https://bpm.example/i-will-follow-you-into-the-dark-yanjoco",
                ),
                result,
            )
        }

    @Test
    fun `a common-word artist needs the distinctive one`() =
        runTest {
            // RegressionCase.FEATURED_ARTIST, 63: AC/DC's version says "them", not Van Morrison.
            val (result, _) = lookUp(RegressionCase.FEATURED_ARTIST.tracks.first(), "63-baby-please-dont-go")
            assertLines(
                "63-baby-please-dont-go",
                listOf(
                    "https://history.example/songs/baby-please-dont-go-them",
                    "https://blues.example/baby-please-dont-go",
                ),
                result,
            )
        }

    @Test
    fun `a track without a checkable artist sends nothing`() =
        runTest {
            for (artists in listOf("", "!!!")) {
                val net = ReplayNetClient.ok("""{"results":[]}""")
                val result =
                    adapter(net).lookup(normalizer.normalize("Sundown", artists), CompletableDeferred(Resolution.NONE))
                assertEquals(Outcome.None, result.outcome)
                assertTrue(result.evidence.isEmpty())
                assertTrue(net.sent.isEmpty(), "sent a request for artists \"$artists\"")
            }
        }

    @Test
    fun `no results is None`() =
        runTest {
            val result =
                adapter(ReplayNetClient.ok("""{"requestId":"x","results":[]}"""))
                    .lookup(track(23), CompletableDeferred(Resolution.NONE))
            assertEquals(Outcome.None, result.outcome)
            assertTrue(result.evidence.isEmpty())
        }

    @Test
    fun `errors and non-2xx answers are Unreachable`() =
        runTest {
            val clients =
                listOf(
                    ReplayNetClient.failing(NetError.Unauthorized(401)),
                    ReplayNetClient.failing(NetError.RateLimited(null)),
                    ReplayNetClient.failing(NetError.Offline("UnknownHostException")),
                    ReplayNetClient.status(400, """{"error":"bad request"}"""),
                    ReplayNetClient.status(402, """{"error":"out of credits"}"""),
                    ReplayNetClient.ok("not json"),
                )
            for (net in clients) {
                val result = adapter(net).lookup(track(1), CompletableDeferred(Resolution.NONE))
                assertEquals(Outcome.Unreachable, result.outcome)
                assertTrue(result.evidence.isEmpty())
            }
        }

    @Test
    fun `duplicate URLs and results without a highlight give no line`() =
        runTest {
            val body =
                """
                {"results": [
                  {"url": "https://a.example/1", "title": "Sundown by Gordon Lightfoot", "highlights": []},
                  {"url": "https://a.example/2", "title": "Sundown by Gordon Lightfoot", "highlights": ["  Sundown,\n  1974  "]},
                  {"url": "https://a.example/2", "title": "Sundown by Gordon Lightfoot", "highlights": ["again"]},
                  {"url": "https://en.wikipedia.org/wiki/Sundown", "title": "Sundown by Gordon Lightfoot", "highlights": ["x"]}
                ]}
                """.trimIndent()
            val result = adapter(ReplayNetClient.ok(body)).lookup(track(1), CompletableDeferred(Resolution.NONE))
            assertEquals(listOf("https://a.example/2"), result.evidence.map { it.url })
            assertEquals("Sundown, 1974", result.evidence.single().text)
        }

    @Test
    fun `the query names the song and every credited artist`() {
        val one = ExaSearchRequest.of(track(1))
        assertEquals("\"Sundown\" by Gordon Lightfoot, song", one.query)
        assertEquals("Sundown Gordon Lightfoot", one.contents.highlights.query)

        val two = ExaSearchRequest.of(track(77))
        assertEquals("\"Forever Young\" by dearoldfriend and Winston Hunte, song", two.query)

        val from = ExaSearchRequest.of(track(34))
        assertEquals("\"Theme\" from Coronation Street by London Music Works, song", from.query)
        assertEquals("Theme Coronation Street London Music Works", from.contents.highlights.query)

        val three = ExaSearchRequest.of(track(37))
        assertEquals(
            "\"The Ode To Newfoundland\" by Charles Cavendish Boyle, Hubert Parry and Cantus Vocum Chamber Choir, song",
            three.query,
        )
        assertEquals("instant", three.type)
        assertEquals(RecordingFilter.OWN_SOURCE_HOSTS, three.excludeDomains)
    }

    private companion object {
        val RECORDINGS =
            listOf(
                1 to "01-sundown",
                12 to "12-bad-energy",
                23 to "23-fishin-in-a-dory",
                30 to "30-the-veil-between-worlds",
                34 to "34-theme",
                45 to "45-i-will-follow-you-into-the-dark",
                63 to "63-baby-please-dont-go",
                77 to "77-forever-young",
            )
    }
}
