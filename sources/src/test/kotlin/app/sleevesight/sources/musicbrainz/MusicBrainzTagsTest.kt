package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.MusicBrainzLabels
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.FirstRelease
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
 * `MusicBrainzClient.artistTags`, the one call that asks for tags (`inc=tags`), the genre labels
 * from them, and the adapter's naming of the artists they are asked for (#84). The answers are
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
            assertEquals(listOf("mb-a"), result.labelArtists)
            assertTrue(result.genres.isEmpty())
            assertEquals(3, MusicBrainzSource().extractionVersion)
        }

    @Test
    fun `the adapter keeps the first release's date, for a track later served from the cache`() =
        runTest {
            val released = RESOLVED.copy(firstRelease = FirstRelease("1974-01", "Sundown", null, "rel", "Album"))
            assertEquals("1974-01", MusicBrainzSource().lookup(TRACK, CompletableDeferred(released)).firstReleased)
            assertEquals(null, MusicBrainzSource().lookup(TRACK, CompletableDeferred(RESOLVED)).firstReleased)
        }

    @Test
    fun `the client's tags give the labels, linking the artist's page, never as lines`() =
        runTest {
            val labels = MusicBrainzLabels(MusicBrainzClient(ReplayNetClient.ok(ARTIST)), vocabulary)
            val found = labels.labels(listOf("mb-a"))
            assertEquals(listOf("Folk", "Country"), found.labels.map { it.label })
            assertEquals(listOf(MusicBrainzLabels.artistPage("mb-a")), found.labels.map { it.url }.distinct())
            assertTrue(found.complete)
        }

    private companion object {
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
