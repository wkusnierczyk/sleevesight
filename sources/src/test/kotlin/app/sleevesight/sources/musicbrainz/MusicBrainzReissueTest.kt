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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * *Beat It* by Michael Jackson from *Thriller 25 Super Deluxe Edition* (#1065), against MusicBrainz's
 * searches as it answered them (`fixtures/http/musicbrainz-1065/`): a first page of 100 of 107
 * recordings, with the album's track 5 (`de798de6…`, 4:18, first released on *Thriller* in 1982)
 * and its track 14, *Beat It 2008* with Fergie (`9e5ae664…`, 4:11), and the search by 1982-11-30.
 */
class MusicBrainzReissueTest {
    private val net = ReplayNetClient("musicbrainz-1065")
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
    fun `the album's Beat It resolves to the 1982 recording, not Beat It 2008`() =
        runTest {
            val track = Normalizer(JvmAccentFolder).normalize("Beat It", "Michael Jackson")
            val resolution = RecordingResolver(searchOnly, RecordedTracks.names).resolve(track, 258.seconds)
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("de798de6-70fc-49f6-b580-339cd2e62052", resolution.recordingMbid)
            assertEquals("1982-11-30", resolution.firstRelease?.date)
            assertEquals("Thriller", resolution.firstRelease?.title)
            // The reissue is in the first release's group (#1065), which *Dates* weighs with the year.
            val deluxe = resolution.releaseDates.single { it.title == "Thriller 25 Super Deluxe Edition" }
            assertEquals(resolution.firstRelease?.releaseGroupMbid, deluxe.group)
            assertTrue(deluxe.date.orEmpty().startsWith("2008"))
        }
}
