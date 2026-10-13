package io.github.wkusnierczyk.sleevesight.sources.web

import io.github.wkusnierczyk.sleevesight.core.Secret
import io.github.wkusnierczyk.sleevesight.core.evidence.Scope
import io.github.wkusnierczyk.sleevesight.core.model.NormalizedTrack
import io.github.wkusnierczyk.sleevesight.core.model.Outcome
import io.github.wkusnierczyk.sleevesight.core.model.SourceId
import io.github.wkusnierczyk.sleevesight.core.normalizer.Normalizer
import io.github.wkusnierczyk.sleevesight.core.platform.JvmAccentFolder
import io.github.wkusnierczyk.sleevesight.core.sources.Resolution
import io.github.wkusnierczyk.sleevesight.core.sources.SourceResult
import io.github.wkusnierczyk.sleevesight.fixtures.BelowSoundtrack
import io.github.wkusnierczyk.sleevesight.fixtures.Fixtures
import io.github.wkusnierczyk.sleevesight.fixtures.RegressionCase
import io.github.wkusnierczyk.sleevesight.net.NetError
import io.github.wkusnierczyk.sleevesight.net.NetMethod
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

    /** The recorded result for [url]: its highlights joined as the adapter joins them. */
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
                    "https://open.spotify.com/track/6gLrz6fdavhgbQo1RsbmI6",
                    "https://www.songfacts.com/facts/gordon-lightfoot/sundown",
                    "https://www.youtube.com/watch?v=1IBdZ645S-o",
                    "https://www.youtube.com/watch?v=MeRlumGqZlY",
                    "https://www.allmusic.com/song/sundown-mt0000540717",
                ),
                result,
            )
            assertEquals(ExaWebSearch.MAX_LINES, result.evidence.size)
        }

    @Test
    fun `same title by other artists is dropped`() =
        runTest {
            // RegressionCase.SAME_NAME_ARTIST: here the other "Bad Energy"s are Juice WRLD's and others'.
            val (result, _) = lookUp(RegressionCase.SAME_NAME_ARTIST.tracks.first(), "12-bad-energy")
            assertEquals(Outcome.SongArticle, result.outcome)
            assertLines(
                "12-bad-energy",
                listOf(
                    "https://www.top-charts.com/s/bad-energy-reem",
                    "https://www.lyricsbull.com/bad-energy-lyrics-reem/",
                ),
                result,
            )
        }

    @Test
    fun `thin tracks keep only the listings that name the recording`() =
        runTest {
            // RegressionCase.NOTHING_DOCUMENTED: 23 and 30.
            val (dory, _) = lookUp(23, "23-fishin-in-a-dory")
            assertEquals(Outcome.SongArticle, dory.outcome)
            assertLines(
                "23-fishin-in-a-dory",
                listOf(
                    "https://what-song.com/song/763387/fishin-in-a-dory",
                    "https://catalog.nols.org/Hoopla/19598773",
                    "https://what-song.com/Artist/242496/Ray-Johnson-And-The-Sou-Westers",
                    "https://soundtrackost.com/below-songs-netflix/",
                ),
                dory,
            )

            val (veil, _) = lookUp(30, "30-the-veil-between-worlds")
            assertEquals(Outcome.SongArticle, veil.outcome)
            assertLines(
                "30-the-veil-between-worlds",
                listOf(
                    "https://what-song.com/song/763393/the-veil-between-worlds",
                    "https://critiquebrainz.org/recording/6ef5fd9d-26a6-4c19-b6b5-82dafa8d44e6",
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
                77 to "77-forever-young",
            )
    }
}
