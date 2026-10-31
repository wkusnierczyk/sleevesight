package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.MusicBrainzLabels
import app.sleevesight.core.genres.RecordingTags
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbRecordingDetail
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.OriginalRecording
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.net.NetError
import app.sleevesight.sources.web.ReplayNetClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `MusicBrainzClient.artistTags` and the recording's lookup, the calls that ask for tags
 * (`inc=tags`), the genre labels from them, and the adapter's naming of the artists they are asked
 * for (#84) and its carrying of the recording's (#439). The answers are
 * written for the test in the shape of MusicBrainz's: its tags are CC BY-NC-SA 3.0, and none is
 * committed.
 */
class MusicBrainzTagsTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    @Test
    fun `the tags are asked for on the artist and read most voted first, ties by name, without those voted down`() =
        runTest {
            val net = ReplayNetClient.ok(ARTIST)
            val tags = MusicBrainzClient(net).artistTags("mb-a")

            assertEquals(
                listOf("folk", "canadian", "country", "seen live"),
                assertIs<Lookup.Found<List<String>>>(tags).value,
            )
            val request = net.sent.single()
            assertEquals("${MusicBrainzClient.BASE}/artist/mb-a", request.url)
            assertEquals(mapOf("inc" to "tags", "fmt" to "json"), request.query)
        }

    @Test
    fun `an artist with no tags, an unknown artist, or a failed request`() =
        runTest {
            assertEquals(
                Lookup.Found(emptyList()),
                MusicBrainzClient(ReplayNetClient.ok("""{"id":"mb-a"}""")).artistTags("mb-a"),
            )
            assertEquals(Lookup.NotFound, MusicBrainzClient(ReplayNetClient.status(404, "")).artistTags("mb-x"))
            assertIs<Lookup.Failed>(MusicBrainzClient(ReplayNetClient.ok("""{"tags":[]}""")).artistTags("mb-a"))
            assertIs<Lookup.Failed>(
                MusicBrainzClient(ReplayNetClient.failing(NetError.Offline("UnknownHostException"))).artistTags("mb-a"),
            )
            assertIs<Lookup.Failed>(MusicBrainzClient(ReplayNetClient.status(503, "")).artistTags("mb-a"))
        }

    @Test
    fun `the adapter names the resolved artists for the labels, and asks for no tags itself`() =
        runTest {
            val result = MusicBrainzSource().lookup(TRACK, CompletableDeferred(RESOLVED))
            assertEquals(listOf(ResolvedArtist("Gordon Lightfoot", "mb-a")), result.labelArtists)
            assertTrue(result.genres.isEmpty())
            assertEquals(null, result.recordingTags)
            assertEquals(7, MusicBrainzSource().extractionVersion)
        }

    @Test
    fun `the recording's lookup brings its tags, which the adapter carries for the track's labels, never as lines`() =
        runTest {
            val net = ReplayNetClient.ok(RECORDING)
            val detail = assertIs<Lookup.Found<MbRecordingDetail>>(MusicBrainzClient(net).recording("rec")).value
            assertEquals(listOf("folk", "soft rock"), detail.tags)
            assertEquals("artist-credits+work-rels+artist-rels+url-rels+tags", net.sent.single().query["inc"])

            val result =
                MusicBrainzSource().lookup(
                    TRACK,
                    CompletableDeferred(RESOLVED.copy(recordingTags = detail.tags)),
                )
            assertEquals(RecordingTags("rec", listOf("folk", "soft rock")), result.recordingTags)
            assertTrue(result.evidence.none { "folk" in it.text.lowercase() || "soft rock" in it.text.lowercase() })
        }

    @Test
    fun `with genres off the recording's lookup asks for no tags, and the adapter keeps none, apart`() =
        runTest {
            val net = ReplayNetClient.ok(RECORDING)
            var genres = false
            MusicBrainzClient(net) { genres }.recording("rec")
            genres = true
            MusicBrainzClient(net) { genres }.recording("rec")
            assertEquals(
                listOf(
                    "artist-credits+work-rels+artist-rels+url-rels",
                    "artist-credits+work-rels+artist-rels+url-rels+tags",
                ),
                net.sent.map { it.query["inc"] },
            )

            val off = MusicBrainzSource(genres = false)
            val result = off.lookup(TRACK, CompletableDeferred(RESOLVED.copy(recordingTags = listOf("folk"))))
            assertEquals(null, result.recordingTags)
            assertTrue(off.extractionVersion != MusicBrainzSource().extractionVersion)
        }

    @Test
    fun `the adapter keeps the release dates, for a track later served from the cache`() =
        runTest {
            val dates = listOf(ReleaseDate("Gord's Gold", "1975", "rg-2"), ReleaseDate("Sundown", "1974-01", "rg-1"))
            val first = FirstRelease("1974-01", "Sundown", null, "rel", "Album")
            val original =
                OriginalRecording(
                    "mb-o",
                    "Sundown",
                    emptyList(),
                    "Someone First",
                    "1970",
                    FirstRelease("1970", "Sundown", "rg-o", "rel-o", "Single"),
                )
            val released = RESOLVED.copy(firstRelease = first, releaseDates = dates, original = original)
            val kept = MusicBrainzSource().lookup(TRACK, CompletableDeferred(released))
            assertEquals("1974-01", kept.firstReleased)
            assertEquals(dates, kept.releaseDates)
            // And what the home screen's *Dates* says of them (#724).
            assertEquals(first, kept.firstRelease)
            assertEquals(original, kept.original)
            val none = MusicBrainzSource().lookup(TRACK, CompletableDeferred(RESOLVED))
            assertEquals(null, none.firstReleased)
            assertEquals(emptyList<ReleaseDate>(), none.releaseDates)
            assertEquals(null, none.firstRelease)
            assertEquals(null, none.original)
        }

    @Test
    fun `the client's tags give the labels, linking the artist's page, never as lines`() =
        runTest {
            val labels = MusicBrainzLabels(MusicBrainzClient(ReplayNetClient.ok(ARTIST)), vocabulary)
            val found = labels.labels(listOf(ResolvedArtist("Gordon Lightfoot", "mb-a")))
            assertEquals(listOf("Folk", "Country"), found.map { it.label })
            assertEquals(listOf(MusicBrainzLabels.artistPage("mb-a")), found.map { it.url }.distinct())
        }

    private companion object {
        // Written for the test in the recording lookup's shape; not MusicBrainz's data.
        const val RECORDING =
            """{"id":"rec","title":"Sundown","artist-credit":[],"relations":[],"tags":[
              {"name":"soft rock","count":1},{"name":"folk","count":3},{"name":"bad","count":0}]}"""

        val TRACK =
            NormalizedTrack(
                "Sundown",
                listOf("Gordon Lightfoot"),
                "Gordon Lightfoot",
                null,
                null,
                emptyList(),
                "sundown",
            )

        val RESOLVED =
            Resolution(
                outcome = Outcome.SongArticle,
                recordingMbid = "rec",
                artists = listOf(ResolvedArtist("Gordon Lightfoot", "mb-a")),
            )

        // Written for the test in the artist lookup's shape (`inc=tags`); not MusicBrainz's data.
        const val ARTIST =
            """{"id":"mb-a","name":"An Artist","type":"Person","tags":[
              {"name":"canadian","count":2},{"name":"folk","count":7},{"name":"seen live","count":1},
              {"name":"country","count":2},{"name":"bagel","count":0},{"name":"pop","count":-1}]}"""
    }
}
