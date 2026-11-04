// Lengths are the data here, not magic numbers.
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
import app.sleevesight.sources.RecordedTracks
import app.sleevesight.sources.testing.ReplayNetClient
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The search for the recordings released by a date (#906), against *Dream On* by Aerosmith as
 * MusicBrainz answered (`fixtures/http/musicbrainz-906/`): a first page of 100 of its 169
 * recordings that holds none of the 1973 studio one, and the search by the first page's earliest
 * date that could be chosen (the 1978 live take's), which does.
 */
class MusicBrainzEarlierTest {
    private val net = ReplayNetClient("musicbrainz-906")
    private val client = MusicBrainzClient(net)

    // The client's two searches, with nothing else known of the recordings.
    private val searchOnly =
        object : MusicBrainzLookup {
            override suspend fun searchRecordings(
                title: String,
                artist: String,
            ): Lookup<List<MbRecording>> = client.searchRecordings(title, artist)

            override suspend fun searchRecordingsBefore(
                title: String,
                artist: String,
                date: String,
            ): Lookup<List<MbRecording>> = client.searchRecordingsBefore(title, artist, date)

            override suspend fun recording(mbid: String): Lookup<MbRecordingDetail> = Lookup.NotFound

            override suspend fun recordingReleases(mbid: String): Lookup<MbRecording> = Lookup.NotFound

            override suspend fun work(mbid: String): Lookup<MbWork> = Lookup.NotFound

            override suspend fun searchArtists(name: String): Lookup<MbArtistSearch> = Lookup.NotFound
        }

    @Test
    fun `a studio track past the first page resolves to its studio recording`() =
        runTest {
            val track = Normalizer(JvmAccentFolder).normalize("Dream On", "Aerosmith")
            val resolution = RecordingResolver(searchOnly, RecordedTracks.names).resolve(track, 267.seconds)
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("e135871f-0fed-4683-90e3-ebbe435fbe4e", resolution.recordingMbid)
            assertEquals("1973-01-05", resolution.firstReleaseDate)
            val queries = net.sent.map { it.query["query"] }
            assertEquals(2, queries.size)
            assertEquals("recording:\"Dream On\" AND artist:\"Aerosmith\"", queries.first())
        }

    @Test
    fun `the search by date asks for the recordings first released on or before it`() =
        runTest {
            client.searchRecordingsBefore("Dream On", "Aerosmith", "1978-10")
            assertEquals(
                "recording:\"Dream On\" AND artist:\"Aerosmith\" AND firstreleasedate:[* TO 1978-10]",
                net.sent.single().query["query"],
            )
        }

    @Test
    fun `a date that is not MusicBrainz's is not asked`() =
        runTest {
            assertEquals(Lookup.Failed("InvalidDate"), client.searchRecordingsBefore("Song", "Artist", "1978] OR x:[*"))
            assertEquals(emptyList(), net.sent)
        }
}
