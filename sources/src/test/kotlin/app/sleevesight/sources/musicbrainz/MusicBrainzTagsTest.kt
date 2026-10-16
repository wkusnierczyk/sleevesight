package app.sleevesight.sources.musicbrainz

import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.MusicBrainzLabels
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.net.NetError
import app.sleevesight.sources.web.ReplayNetClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `MusicBrainzClient.artistTags`, the one call that asks for tags (`inc=tags`), and the adapter's
 * genre labels from them (#84). The answers are written for the test in the shape of MusicBrainz's:
 * its tags are CC BY-NC-SA 3.0, and none is committed.
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
    fun `the adapter's result holds the resolved artists' labels, never as lines`() =
        runTest {
            val source =
                MusicBrainzSource(labels = MusicBrainzLabels(MusicBrainzClient(ReplayNetClient.ok(ARTIST)), vocabulary))
            val result = source.lookup(TRACK, CompletableDeferred(RESOLVED))

            assertEquals(listOf("Folk", "Country"), result.genres.map { it.label })
            assertEquals(listOf(MusicBrainzLabels.artistPage("mb-a")), result.genres.map { it.url }.distinct())
            assertFalse(result.evidence.any { "folk" in it.text.lowercase() || "country" in it.text.lowercase() })
            assertFalse(result.provisional)
            assertEquals(2, source.extractionVersion)
        }

    @Test
    fun `a failed tag request leaves the result provisional, so it is not kept`() =
        runTest {
            val offline = MusicBrainzClient(ReplayNetClient.failing(NetError.Offline("UnknownHostException")))
            val result =
                MusicBrainzSource(
                    labels = MusicBrainzLabels(offline, vocabulary),
                ).lookup(TRACK, CompletableDeferred(RESOLVED))
            assertEquals(Outcome.SongArticle, result.outcome)
            assertTrue(result.genres.isEmpty())
            assertTrue(result.provisional)
        }

    @Test
    fun `with MusicBrainz labels off, the adapter asks for no tags and has the version of a result without them`() =
        runTest {
            val net = ReplayNetClient.ok(ARTIST)
            val source = MusicBrainzSource(labels = MusicBrainzLabels(MusicBrainzClient(net), vocabulary))
            assertSame(source, source.configuredFor(Settings()))

            val off = source.configuredFor(Settings(labelSources = setOf(LabelSource.LASTFM)))
            assertEquals(1, off.extractionVersion)
            val result = off.lookup(TRACK, CompletableDeferred(RESOLVED))
            assertTrue(result.genres.isEmpty())
            assertTrue(net.sent.isEmpty())
            // Without a tag client (the CLI), the same.
            assertEquals(1, MusicBrainzSource().extractionVersion)
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
