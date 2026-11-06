package app.sleevesight.sources.lrclib

import app.sleevesight.core.lyrics.LibraryLyrics
import app.sleevesight.core.lyrics.LibraryQuery
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * LRCLIB's `/api/get` (#997): asked by title, artist, album and length in seconds; its instrumental
 * mark, lyrics or neither read from the record; a 404 no match; anything else a failure.
 */
class LrclibClientTest {
    /** A client that answers [status] and [body] to every request, recording each. */
    private class Answering(
        private val status: Int,
        private val body: String = "",
        private val failure: NetError? = null,
    ) : NetClient {
        val sent = mutableListOf<NetRequest>()

        override suspend fun send(request: NetRequest): NetResult<NetResponse> {
            sent += request
            return failure?.let { NetResult.Failure(it) }
                ?: NetResult.Success(NetResponse(status, emptyMap(), body.encodeToByteArray()))
        }

        override fun close() = Unit
    }

    private val query = LibraryQuery("Sababa 5", "Nasnusa", "Kokoro", 234)

    private fun record(
        instrumental: Boolean,
        plain: String?,
        synced: String?,
    ): String {
        fun quoted(text: String?) = text?.let { "\"$it\"" } ?: "null"
        return """{"id":1,"trackName":"Nasnusa","artistName":"Sababa 5","albumName":"Kokoro","duration":234.0,""" +
            """"instrumental":$instrumental,"plainLyrics":${quoted(plain)},"syncedLyrics":${quoted(synced)}}"""
    }

    private suspend fun answer(
        status: Int,
        body: String = "",
    ): Lookup<LibraryLyrics> = LrclibClient(Answering(status, body)).lookUp(query)

    @Test
    fun `the request names the title, the artist, the album and the length in seconds`() =
        runTest {
            val net = Answering(404)
            LrclibClient(net).lookUp(query)
            val sent = net.sent.single()
            assertEquals("https://lrclib.net/api/get", sent.url)
            assertEquals(
                mapOf(
                    "track_name" to "Nasnusa",
                    "artist_name" to "Sababa 5",
                    "album_name" to "Kokoro",
                    "duration" to "234",
                ),
                sent.query,
            )
            // With no album from the player, none is sent.
            LrclibClient(net).lookUp(query.copy(album = null))
            assertEquals(
                setOf("track_name", "artist_name", "duration"),
                net.sent
                    .last()
                    .query.keys,
            )
        }

    @Test
    fun `a record's instrumental mark, its lyrics, or neither`() =
        runTest {
            assertEquals(Lookup.Found(LibraryLyrics.INSTRUMENTAL), answer(200, record(true, null, null)))
            assertEquals(Lookup.Found(LibraryLyrics.LYRICS), answer(200, record(false, "Words", null)))
            assertEquals(Lookup.Found(LibraryLyrics.LYRICS), answer(200, record(false, " ", "[00:01.00] Words")))
            assertEquals(Lookup.Found(LibraryLyrics.NONE), answer(200, record(false, " ", null)))
            // The mark wins over lyrics the record holds anyway.
            assertEquals(Lookup.Found(LibraryLyrics.INSTRUMENTAL), answer(200, record(true, "Words", null)))
        }

    @Test
    fun `a 404 is no match, and an error or no answer a failure`() =
        runTest {
            val notFound = """{"message":"Failed to find specified track","name":"TrackNotFound","statusCode":404}"""
            assertEquals(Lookup.NotFound, answer(404, notFound))
            assertIs<Lookup.Failed>(answer(500))
            assertIs<Lookup.Failed>(answer(200, "not json"))
            val offline = Answering(0, failure = NetError.Offline("UnknownHostException"))
            assertIs<Lookup.Failed>(LrclibClient(offline).lookUp(query))
        }

    @Test
    fun `its host's terms ask for the site in the User-Agent and jitter on a 429`() {
        assertEquals("https://sleevesight.app", LrclibClient.HOST_POLICY.contact)
        assertEquals(1.seconds, LrclibClient.HOST_POLICY.retryJitter)
    }
}
