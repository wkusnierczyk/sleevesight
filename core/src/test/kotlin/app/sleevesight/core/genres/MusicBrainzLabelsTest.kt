package app.sleevesight.core.genres

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicBrainzLabelsTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    // Tags written for the test, not MusicBrainz's (CC BY-NC-SA 3.0: none is committed).
    private val tags =
        mapOf<String, Lookup<List<String>>>(
            "mb-a" to Lookup.Found(listOf("folk", "canadian", "country")),
            "mb-b" to Lookup.Found(listOf("country", "rock")),
            "mb-gone" to Lookup.NotFound,
            "mb-down" to Lookup.Failed("offline"),
        )
    private val asked = mutableListOf<String>()
    private val labels =
        MusicBrainzLabels(
            { mbid ->
                asked += mbid
                tags.getValue(mbid)
            },
            vocabulary,
        )

    private fun resolution(vararg artists: Pair<String, String?>) =
        Resolution(
            outcome = Outcome.SongArticle,
            recordingMbid = "rec",
            artists = artists.map { (name, mbid) -> ResolvedArtist(name, mbid) },
        )

    @Test
    fun `each resolved artist's tags, in credit order, each genre once, linking its artist's page`() =
        runTest {
            val found = labels.labels(resolution("A" to "mb-a", "Nobody" to null, "B" to "mb-b", "A again" to "mb-a"))
            assertEquals(listOf("Folk", "Country", "Rock"), found.labels.map { it.label })
            assertEquals(
                listOf("mb-a", "mb-a", "mb-b").map(MusicBrainzLabels::artistPage),
                found.labels.map { it.url },
            )
            assertTrue(found.complete)
            // An artist with no id is not asked about: its name could be a namesake's.
            assertEquals(listOf("mb-a", "mb-b"), asked)
        }

    @Test
    fun `an artist MusicBrainz does not know gives none, and a failed request leaves the labels incomplete`() =
        runTest {
            val gone = labels.labels(resolution("A" to "mb-a", "Gone" to "mb-gone"))
            assertEquals(listOf("Folk", "Country"), gone.labels.map { it.label })
            assertTrue(gone.complete)

            val down = labels.labels(resolution("Down" to "mb-down", "B" to "mb-b"))
            assertEquals(listOf("Country", "Rock"), down.labels.map { it.label })
            assertFalse(down.complete)
        }

    @Test
    fun `nothing resolved, nothing asked`() =
        runTest {
            assertEquals(MusicBrainzLabels.Found(emptyList(), complete = true), labels.labels(Resolution.NONE))
            assertEquals(emptyList(), asked)
        }

    @Test
    fun `the artist's page is MusicBrainz's`() {
        assertEquals("https://musicbrainz.org/artist/mb-a", MusicBrainzLabels.artistPage("mb-a"))
    }
}
