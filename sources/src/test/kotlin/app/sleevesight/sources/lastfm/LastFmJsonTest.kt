package app.sleevesight.sources.lastfm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LastFmJsonTest {
    @Test
    fun `lists of one as objects, empty lists as strings, numbers as strings`() {
        val body =
            """{"artist":{"name":"X","mbid":"","url":"https://www.last.fm/music/X","stats":{"listeners":"12"},
               "similar":"","tags":{"tag":{"name":"rap","url":"t"}},
               "bio":{"links":{"link":{"href":"https://www.last.fm/music/X/+wiki"}},
               "summary":"X is a rapper from the north of England."}}}"""
        val artist = assertIs<LastFmAnswer.Found<LastFmArtist>>(LastFmJson.artistInfo(body)).value
        // The summary's quotable sentences are kept, not the summary.
        val bio = listOf("X is a rapper from the north of England.")
        val page = "https://www.last.fm/music/X"
        assertEquals(LastFmArtist("X", null, page, 12, listOf("rap"), emptyList(), bio, false, "$page/+wiki"), artist)
    }

    @Test
    fun `an address that is not a web address is dropped where it is read`() {
        val body =
            """{"artist":{"name":"X","url":"intent://x#Intent;package=com.example;end",
               "similar":{"artist":[{"name":"Y","url":"javascript:alert(1)"},
               {"name":"Z","url":"https://www.last.fm/music/Z"}]},
               "bio":{"links":{"link":{"href":"file:///sdcard/x"}}}}}"""
        val artist = assertIs<LastFmAnswer.Found<LastFmArtist>>(LastFmJson.artistInfo(body)).value
        assertNull(artist.url)
        assertNull(artist.bioUrl)
        val similar = listOf(LastFmSimilar("Y", null), LastFmSimilar("Z", "https://www.last.fm/music/Z"))
        assertEquals(similar, artist.similar)
        val album = """{"album":{"name":"A","artist":"X","url":"market://details?id=com.example"}}"""
        assertNull(assertIs<LastFmAnswer.Found<LastFmAlbum>>(LastFmJson.albumInfo(album)).value.url)
    }

    @Test
    fun `missing parts are absent, not errors`() {
        val answer = LastFmJson.artistInfo("""{"artist":{"name":"X"}}""")
        val artist = assertIs<LastFmAnswer.Found<LastFmArtist>>(answer).value
        assertEquals(LastFmArtist("X", null, null, null, emptyList(), emptyList(), emptyList(), false, null), artist)
    }

    @Test
    fun `top tag counts as numbers or strings`() {
        val body = """{"toptags":{"tag":[{"count":100,"name":"rock"},{"count":"7","name":"pop"},{"name":"x"}]}}"""
        assertEquals(
            listOf(LastFmTag("rock", 100), LastFmTag("pop", 7), LastFmTag("x", 0)),
            assertIs<LastFmAnswer.Found<List<LastFmTag>>>(LastFmJson.topTags(body)).value,
        )
    }

    @Test
    fun `an error answer gives its code`() {
        assertEquals(LastFmAnswer.Error(6), LastFmJson.artistInfo("""{"error":6,"message":"not found"}"""))
        assertEquals(LastFmAnswer.Error(-1), LastFmJson.topTags("""{"error":"odd"}"""))
    }
}
