package app.sleevesight.core.genres

import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class MusicBrainzLabelsTest {
    private val vocabulary = GenreVocabulary.load(JvmResourceReader)

    // Tags written for the test, not MusicBrainz's (CC BY-NC-SA 3.0: none is committed).
    private val tags =
        mutableMapOf<String, Lookup<List<String>>>(
            "mb-a" to Lookup.Found(listOf("folk", "canadian", "country")),
            "mb-b" to Lookup.Found(listOf("country", "rock")),
            "mb-gone" to Lookup.NotFound,
            "mb-down" to Lookup.Failed("offline"),
        )
    private val asked = mutableListOf<String>()
    private var now = Instant.parse("2026-10-16T12:00:00Z")
    private val clock =
        object : Clock {
            override fun now() = now
        }

    private fun labels(capacity: Int = MusicBrainzLabels.DEFAULT_CAPACITY) =
        MusicBrainzLabels(
            { mbid ->
                asked += mbid
                tags.getValue(mbid)
            },
            vocabulary,
            clock,
            capacity = capacity,
        )

    @Test
    fun `each artist's tags, in credit order, each genre once, linking its artist's page`() =
        runTest {
            val found = labels().labels(listOf("mb-a", "mb-b", "mb-a"))
            assertEquals(listOf("Folk", "Country", "Rock"), found.labels.map { it.label })
            assertEquals(
                listOf("mb-a", "mb-a", "mb-b").map(MusicBrainzLabels::artistPage),
                found.labels.map { it.url },
            )
            assertTrue(found.complete)
            assertEquals(listOf("mb-a", "mb-b"), asked)
        }

    @Test
    fun `an artist MusicBrainz does not know gives none, and a failed request leaves the labels incomplete`() =
        runTest {
            val labels = labels()
            val gone = labels.labels(listOf("mb-a", "mb-gone"))
            assertEquals(listOf("Folk", "Country"), gone.labels.map { it.label })
            assertTrue(gone.complete)

            val down = labels.labels(listOf("mb-down", "mb-b"))
            assertEquals(listOf("Country", "Rock"), down.labels.map { it.label })
            assertFalse(down.complete)
        }

    @Test
    fun `an artist's tags are asked for once, for a second track too, but a failure is asked again`() =
        runTest {
            val labels = labels()
            labels.labels(listOf("mb-a", "mb-gone", "mb-down"))
            labels.labels(listOf("mb-a", "mb-gone", "mb-down"))
            assertEquals(listOf("mb-a", "mb-gone", "mb-down", "mb-down"), asked)

            tags["mb-down"] = Lookup.Found(listOf("jazz"))
            assertEquals(listOf("Jazz"), labels.labels(listOf("mb-down")).labels.map { it.label })
        }

    @Test
    fun `kept tags expire, and the least recently used artist goes first`() =
        runTest {
            val labels = labels(capacity = 2)
            labels.labels(listOf("mb-a", "mb-b"))
            labels.labels(listOf("mb-a"))
            labels.labels(listOf("mb-gone"))
            asked.clear()
            labels.labels(listOf("mb-a", "mb-b"))
            // mb-b was the least recently used when mb-gone came.
            assertEquals(listOf("mb-b"), asked)

            asked.clear()
            now += MusicBrainzLabels.DEFAULT_LIFETIME + 1.days
            labels.labels(listOf("mb-a"))
            assertEquals(listOf("mb-a"), asked)
        }

    @Test
    fun `no artists, nothing asked`() =
        runTest {
            assertEquals(MusicBrainzLabels.Found(emptyList(), complete = true), labels().labels(emptyList()))
            assertEquals(emptyList(), asked)
        }

    @Test
    fun `the artist's page is MusicBrainz's`() {
        assertEquals("https://musicbrainz.org/artist/mb-a", MusicBrainzLabels.artistPage("mb-a"))
    }
}
