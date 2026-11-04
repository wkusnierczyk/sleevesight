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
 * `LastFmAlbums`: Last.fm's album (`album.getInfo`, #885), for an album's insight. The answers are
 * written for the test in the shape of Last.fm's documentation, as the stubs are
 * (`fixtures/http/lastfm/README.md`); not Last.fm's data.
 */
class LastFmAlbumsTest {
    @Test
    fun `an album by the artist and album, its names, id, page and summary, the key never in the URL`() =
        runTest {
            val net = ReplayNetClient.ok(ALBUM)
            val found = LastFmAlbums(net, Secret("key-1")).albumInfo("Gordon Lightfoot", "Sundown")
            assertEquals(
                Lookup.Found(
                    LastFmAlbum(
                        name = "Sundown",
                        artist = "Gordon Lightfoot",
                        mbid = "11111111-0000-4000-8000-000000000001",
                        url = "https://www.last.fm/music/Gordon+Lightfoot/Sundown",
                        summary =
                            "Sundown is the tenth studio album by Gordon Lightfoot. " +
                                "<a href=\"https://www.last.fm/music/Gordon+Lightfoot/Sundown\">" +
                                "Read more on Last.fm</a>.",
                    ),
                ),
                found,
            )
            val request = net.sent.single()
            assertEquals(LastFmAdapter.API_URL, request.url)
            assertEquals(
                mapOf(
                    "method" to "album.getinfo",
                    "artist" to "Gordon Lightfoot",
                    "album" to "Sundown",
                    "autocorrect" to "1",
                    "format" to "json",
                ),
                request.query,
            )
            assertEquals(mapOf("api_key" to "key-1"), request.secrets.query)
        }

    @Test
    fun `an album with no wiki or id`() =
        runTest {
            val found = LastFmAlbums(ReplayNetClient.ok(BARE), Secret("k")).albumInfo("Peleboy", "Peleboy")
            assertEquals(Lookup.Found(LastFmAlbum("Peleboy", "PeleBoy", null, null, null)), found)
        }

    @Test
    fun `one Last fm does not know, an error, or a failed request`() =
        runTest {
            val albums = { net: ReplayNetClient -> LastFmAlbums(net, Secret("k")) }
            assertEquals(Lookup.NotFound, albums(ReplayNetClient.ok(NOT_FOUND)).albumInfo("A", "B"))
            assertIs<Lookup.Failed>(albums(ReplayNetClient.status(403, INVALID_KEY)).albumInfo("A", "B"))
            assertIs<Lookup.Failed>(albums(ReplayNetClient.status(502, "Bad gateway")).albumInfo("A", "B"))
            assertIs<Lookup.Failed>(
                albums(ReplayNetClient.failing(NetError.Offline("UnknownHostException"))).albumInfo("A", "B"),
            )
        }

    private companion object {
        const val ALBUM =
            """{"album":{"name":"Sundown","artist":"Gordon Lightfoot",
              "mbid":"11111111-0000-4000-8000-000000000001",
              "url":"https://www.last.fm/music/Gordon+Lightfoot/Sundown","listeners":"123456",
              "playcount":"2345678","tags":{"tag":[{"name":"folk","url":"https://www.last.fm/tag/folk"}]},
              "wiki":{"published":"01 Jan 2010, 00:00",
              "summary":"Sundown is the tenth studio album by Gordon Lightfoot. <a href=\"https://www.last.fm/music/Gordon+Lightfoot/Sundown\">Read more on Last.fm</a>.",
              "content":"Sundown is the tenth studio album by Gordon Lightfoot."}}}"""
        const val BARE = """{"album":{"name":"Peleboy","artist":"PeleBoy","mbid":"","url":"","tags":""}}"""
        const val NOT_FOUND = """{"error":6,"message":"Album not found"}"""
        const val INVALID_KEY = """{"error":10,"message":"Invalid API key"}"""
    }
}
