package app.sleevesight.sources.lastfm

import app.sleevesight.core.Secret
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.net.NetError
import app.sleevesight.sources.web.ReplayNetClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `LastFmTags`: the track's and the album's top tags (#439). The answers are written for the test in
 * the shape of Last.fm's `track.getTopTags` and `album.getTopTags`, as the stubs are
 * (`fixtures/http/lastfm/README.md`).
 */
class LastFmTagsTest {
    @Test
    fun `a track's tags, by the artist and title, those weighted too low left out, the key never in the URL`() =
        runTest {
            val net = ReplayNetClient.ok(TAGS)
            val tags = LastFmTags(net, Secret("key-1")).trackTags("The Beatles", "Michelle")
            assertEquals(Lookup.Found(listOf("pop", "ballad")), tags)
            val request = net.sent.single()
            assertEquals(LastFmAdapter.API_URL, request.url)
            assertEquals(
                mapOf(
                    "method" to "track.gettoptags",
                    "artist" to "The Beatles",
                    "track" to "Michelle",
                    "autocorrect" to "1",
                    "format" to "json",
                ),
                request.query,
            )
            assertEquals(mapOf("api_key" to "key-1"), request.secrets.query)
        }

    @Test
    fun `an album's tags, by the artist and album`() =
        runTest {
            val net = ReplayNetClient.ok(TAGS)
            LastFmTags(net, Secret("key-1")).albumTags("The Beatles", "Rubber Soul")
            assertEquals("album.gettoptags", net.sent.single().query["method"])
            assertEquals("Rubber Soul", net.sent.single().query["album"])
        }

    @Test
    fun `one Last fm does not know, an error, or a failed request`() =
        runTest {
            val tags = { net: ReplayNetClient -> LastFmTags(net, Secret("k")) }
            assertEquals(Lookup.NotFound, tags(ReplayNetClient.ok(NOT_FOUND)).trackTags("A", "B"))
            assertIs<Lookup.Failed>(tags(ReplayNetClient.status(403, INVALID_KEY)).albumTags("A", "B"))
            assertIs<Lookup.Failed>(tags(ReplayNetClient.status(502, "Bad gateway")).trackTags("A", "B"))
            assertIs<Lookup.Failed>(
                tags(ReplayNetClient.failing(NetError.Offline("UnknownHostException"))).trackTags("A", "B"),
            )
        }

    @Test
    fun `the pages a label links, as Last fm's own links are written`() {
        val tags = LastFmTags(ReplayNetClient.ok(TAGS), Secret("k"))
        assertEquals("https://www.last.fm/music/The+Beatles/_/Michelle", tags.trackPage("The Beatles", "Michelle"))
        assertEquals(
            "https://www.last.fm/music/Simon+%26+Garfunkel/Bookends",
            tags.albumPage("Simon & Garfunkel", "Bookends"),
        )
    }

    private companion object {
        // Written for the test in the shape Last.fm's documentation gives; not Last.fm's data.
        const val TAGS =
            """{"toptags":{"tag":[{"count":100,"name":"pop"},{"count":40,"name":"ballad"},
              {"count":3,"name":"seen live"}],"@attr":{"artist":"The Beatles","track":"Michelle"}}}"""
        const val NOT_FOUND = """{"error":6,"message":"Track not found"}"""
        const val INVALID_KEY = """{"error":10,"message":"Invalid API key"}"""
    }
}
