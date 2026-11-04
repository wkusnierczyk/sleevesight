// Lengths, counts and offsets are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbArtistSearch
import app.sleevesight.core.resolution.MbRecording
import app.sleevesight.core.resolution.MbRecordingDetail
import app.sleevesight.core.resolution.MbWork
import app.sleevesight.core.resolution.MusicBrainzLookup
import app.sleevesight.core.resolution.RecordingResolver
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetError
import app.sleevesight.net.NetRequest
import app.sleevesight.net.NetResponse
import app.sleevesight.net.NetResult
import app.sleevesight.sources.RecordedTracks
import app.sleevesight.sources.testing.ReplayNetClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The recording search past its first page (#906): *Dream On* by Aerosmith, 169 recordings on
 * MusicBrainz, whose first page of 100 (as recorded, `fixtures/http/musicbrainz-906/`) holds none
 * of the 1973 studio recording; the second page does.
 */
class MusicBrainzPagingTest {
    private val studio = "e135871f-0fed-4683-90e3-ebbe435fbe4e"
    private val dreamOn = Normalizer(JvmAccentFolder).normalize("Dream On", "Aerosmith")

    // The player's 4:27.
    private val length = 267.seconds

    // The client's search over [net], with nothing else known of the recordings, so the choice is the
    // search's alone.
    private class SearchOnly(
        net: NetClient,
    ) : MusicBrainzLookup {
        private val client = MusicBrainzClient(net)

        override suspend fun searchRecordings(
            title: String,
            artist: String,
        ): Lookup<List<MbRecording>> = client.searchRecordings(title, artist)

        override suspend fun recording(mbid: String): Lookup<MbRecordingDetail> = Lookup.NotFound

        override suspend fun recordingReleases(mbid: String): Lookup<MbRecording> = Lookup.NotFound

        override suspend fun work(mbid: String): Lookup<MbWork> = Lookup.NotFound

        override suspend fun searchArtists(name: String): Lookup<MbArtistSearch> = Lookup.NotFound
    }

    // [inner], with every request for a page past the first failing.
    private class FirstPageOnly(
        private val inner: NetClient,
    ) : NetClient {
        override suspend fun send(request: NetRequest): NetResult<NetResponse> =
            if ("offset" in request.query) NetResult.Failure(NetError.Server(503)) else inner.send(request)

        override fun close() = Unit
    }

    @Test
    fun `a studio track is resolved to its studio recording, from the search's second page`() =
        runTest {
            val net = ReplayNetClient("musicbrainz-906")
            val found = MusicBrainzClient(net).searchRecordings("Dream On", "Aerosmith")

            val recordings = (found as Lookup.Found).value
            assertEquals(169, recordings.size)
            assertEquals(recordings.size, recordings.map { it.mbid }.distinct().size)
            assertEquals(listOf(null, "100"), net.sent.map { it.query["offset"] })

            val resolution =
                RecordingResolver(SearchOnly(ReplayNetClient("musicbrainz-906")), RecordedTracks.names)
                    .resolve(dreamOn, length)
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals(studio, resolution.recordingMbid)
            assertEquals("1973-01-05", resolution.firstReleaseDate)
        }

    @Test
    fun `with the first page alone, a later recording would be chosen, as before the change`() =
        runTest {
            val resolution =
                RecordingResolver(SearchOnly(FirstPageOnly(ReplayNetClient("musicbrainz-906"))), RecordedTracks.names)
                    .resolve(dreamOn, length)
            // A later page that fails leaves the first, as the search was before: the choice is made from it.
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertNotEquals(studio, resolution.recordingMbid)
            assertTrue(checkNotNull(resolution.firstReleaseDate) > "1973-01-05")
        }

    @Test
    fun `a search with fewer recordings than a page asks for one page`() =
        runTest {
            val net = ReplayNetClient("musicbrainz")
            MusicBrainzClient(net).searchRecordings("Fisherman's Blues", "The Waterboys")
            assertEquals(listOf(null), net.sent.map { it.query["offset"] })
        }

    @Test
    fun `at most three pages are asked for, however many recordings match`() =
        runTest {
            val net = Paged(count = 1_000)
            val found = MusicBrainzClient(net).searchRecordings("Song", "Artist")
            assertEquals(listOf(null, "100", "200"), net.sent.map { it.query["offset"] })
            assertEquals(300, (found as Lookup.Found).value.size)
        }

    // A search of [count] recordings, each page its own 100.
    private class Paged(
        private val count: Int,
    ) : NetClient {
        val sent = mutableListOf<NetRequest>()

        override suspend fun send(request: NetRequest): NetResult<NetResponse> {
            sent += request
            val offset = request.query["offset"]?.toInt() ?: 0
            val recordings =
                (offset until offset + 100).joinToString(",") {
                    """{"id":"rec-$it","title":"Song","score":100,"artist-credit":[]}"""
                }
            val body = """{"count":$count,"recordings":[$recordings]}""".encodeToByteArray()
            return NetResult.Success(NetResponse(200, mapOf("content-type" to listOf("application/json")), body))
        }

        override fun close() = Unit
    }
}
