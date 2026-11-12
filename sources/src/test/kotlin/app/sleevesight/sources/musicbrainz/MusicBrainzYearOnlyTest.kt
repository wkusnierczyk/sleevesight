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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * *Way down We Go* by KALEO from *A/B* (#1203), against MusicBrainz's recording search as it answered
 * on 2026-11-12 (`fixtures/http/musicbrainz-1203/`). Three recordings fit a 3:39 track: the single's
 * (`9bdba438…`) and the album's (`efdbd424…`), both first released on 7 August 2015, and *FIFA 16
 * Soundtrack*'s (`af53e3e1…`), dated `2015` alone and on no dated release in the search. The year
 * alone read as earlier than any day in it, so the soundtrack's was chosen, and with it no first
 * release.
 */
class MusicBrainzYearOnlyTest {
    private val net = ReplayNetClient("musicbrainz-1203")
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

    private suspend fun resolve(length: kotlin.time.Duration) =
        RecordingResolver(searchOnly, RecordedTracks.names)
            .resolve(Normalizer(JvmAccentFolder).normalize("Way down We Go", "KALEO"), length)

    @Test
    fun `a recording dated by its year alone is not taken as earlier than one dated in that year`() =
        runTest {
            // The soundtrack's recording is 3:39 to the millisecond: only its date could lose it.
            val resolution = resolve(219.seconds)
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("efdbd424-8a44-4f9f-88f8-902928fe9440", resolution.recordingMbid)
        }

    @Test
    fun `the track's first release is the single, before the album`() =
        runTest {
            for (length in listOf(219.seconds, 219_560.milliseconds)) {
                val first = resolve(length).firstRelease
                assertEquals("2015-08-07", first?.date, "at $length")
                assertEquals("way down we go", first?.title?.lowercase(), "at $length")
                assertEquals("Single", first?.type, "at $length")
                assertEquals("5ad302da-ed70-44c8-8849-fefe7494dff6", first?.releaseGroupMbid, "at $length")
            }
        }

    @Test
    fun `the album's January 1, beside its releases of 10 June that year, is said as 2016`() =
        runTest {
            // MusicBrainz dates *A/B*'s earliest release, a digital one in Iceland, `2016-01-01`, and
            // its other 2016 releases `2016-06-10`: the day is a placeholder, and *Dates* says the
            // year (#1203), never the later release's day. The 2026 deluxe edition keeps its own.
            val ab = resolve(219.seconds).releaseDates.filter { it.group == "6e65ab09-d837-43d4-a541-b93f5f8b2672" }
            assertEquals(
                mapOf("A/B" to "2016", "A/B (Deluxe Edition)" to "2026-06-10"),
                ab.associate { it.title to it.date },
            )
        }
}
