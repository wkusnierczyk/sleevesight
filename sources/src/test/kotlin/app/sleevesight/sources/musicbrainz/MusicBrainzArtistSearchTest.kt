package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbArtist
import app.sleevesight.core.resolution.MbArtistSearch
import app.sleevesight.net.NetError
import app.sleevesight.sources.web.ReplayNetClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** `MusicBrainzClient.searchArtists`, on answers in the shape of MusicBrainz's artist search. */
class MusicBrainzArtistSearchTest {
    @Test
    fun `the search asks by name, quoted, and reads the total and each artist's id and own name`() =
        runTest {
            val net = ReplayNetClient.ok(BODY)
            val found = MusicBrainzClient(net).searchArtists("Reem \"R\"")

            assertEquals(
                MbArtistSearch(
                    total = 3,
                    artists = listOf(MbArtist("a-1", "Reem"), MbArtist("a-2", "Reem"), MbArtist("a-3", "Reem Kelani")),
                ),
                assertIs<Lookup.Found<MbArtistSearch>>(found).value,
            )
            val request = net.sent.single()
            assertEquals("${MusicBrainzClient.BASE}/artist", request.url)
            assertEquals(
                mapOf("query" to "artist:\"Reem \\\"R\\\"\"", "limit" to "100", "fmt" to "json"),
                request.query,
            )
        }

    @Test
    fun `a backslash is escaped`() =
        runTest {
            val net = ReplayNetClient.ok(BODY)
            MusicBrainzClient(net).searchArtists("A\\B \"C\"")
            assertEquals(
                mapOf("query" to "artist:\"A\\\\B \\\"C\\\"\"", "limit" to "100", "fmt" to "json"),
                net.sent.single().query,
            )
        }

    @Test
    fun `no artists, or a failed request`() =
        runTest {
            val empty = MusicBrainzClient(ReplayNetClient.ok("""{"count":0,"offset":0,"artists":[]}"""))
            assertEquals(Lookup.Found(MbArtistSearch(0, emptyList())), empty.searchArtists("Nobody Here"))
            // Without a total, or without the artists, the answer cannot show what matches; nor with
            // a total below the artists it lists.
            assertIs<Lookup.Failed>(MusicBrainzClient(ReplayNetClient.ok("""{"artists":[]}""")).searchArtists("Reem"))
            val two = """[{"id":"a","name":"A"},{"id":"b","name":"B"}]"""
            for (body in listOf("""{"count":1,"artists":$two}""", """{"count":-1,"artists":[]}""")) {
                assertIs<Lookup.Failed>(MusicBrainzClient(ReplayNetClient.ok(body)).searchArtists("Reem"), body)
            }
            assertIs<Lookup.Failed>(MusicBrainzClient(ReplayNetClient.ok("""{"count":5}""")).searchArtists("Reem"))
            assertIs<Lookup.Failed>(
                MusicBrainzClient(ReplayNetClient.ok("""{"count":5,"artists":{}}""")).searchArtists("Reem"),
            )
            val offline = MusicBrainzClient(ReplayNetClient.failing(NetError.Offline("UnknownHostException")))
            assertIs<Lookup.Failed>(offline.searchArtists("Reem"))
            assertIs<Lookup.Failed>(MusicBrainzClient(ReplayNetClient.status(503, "")).searchArtists("Reem"))
        }

    private companion object {
        // Hand-written in the search's shape: aliases are not the artist's own name and are not read.
        const val BODY =
            """{"created":"2026-10-15T00:00:00.000Z","count":3,"offset":0,"artists":[
              {"id":"a-1","type":"Person","score":100,"name":"Reem","sort-name":"Reem",
               "disambiguation":"Danish rapper","aliases":[{"name":"REEM","sort-name":"REEM"}]},
              {"id":"a-2","type":"Person","score":100,"name":"Reem","sort-name":"Reem",
               "disambiguation":"Lebanese singer"},
              {"id":"a-3","type":"Person","score":81,"name":"Reem Kelani","sort-name":"Kelani, Reem"}]}"""
    }
}
