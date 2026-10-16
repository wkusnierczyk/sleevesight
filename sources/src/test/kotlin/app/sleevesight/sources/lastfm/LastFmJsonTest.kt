package app.sleevesight.sources.lastfm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LastFmJsonTest {
    @Test
    fun `lists of one as objects, empty lists as strings, numbers as strings`() {
        val body =
            """{"artist":{"name":"X","mbid":"","url":"u","stats":{"listeners":"12"},"similar":"",
               "tags":{"tag":{"name":"rap","url":"t"}},
               "bio":{"links":{"link":{"href":"w"}},"summary":"s"}}}"""
        val artist = assertIs<LastFmAnswer.Found<LastFmArtist>>(LastFmJson.artistInfo(body)).value
        assertEquals(LastFmArtist("X", null, "u", 12, listOf("rap"), emptyList(), "s", "w"), artist)
    }

    @Test
    fun `missing parts are absent, not errors`() {
        val answer = LastFmJson.artistInfo("""{"artist":{"name":"X"}}""")
        val artist = assertIs<LastFmAnswer.Found<LastFmArtist>>(answer).value
        assertEquals(LastFmArtist("X", null, null, null, emptyList(), emptyList(), null, null), artist)
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
