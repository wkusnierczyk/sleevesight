package app.sleevesight.core.genres

import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.testing.MemoryArtistResults
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
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

    private fun labels(
        capacity: Int = MusicBrainzLabels.DEFAULT_CAPACITY,
        kept: ArtistResults = ArtistResults.None,
    ) = MusicBrainzLabels(
        { mbid ->
            asked += mbid
            tags.getValue(mbid)
        },
        vocabulary,
        clock,
        capacity = capacity,
        artists = kept,
    )

    @Test
    fun `each artist's tags, in credit order, each genre once, linking its artist's page`() =
        runTest {
            val found = labels().labels(listOf("mb-a", "mb-b", "mb-a"))
            assertEquals(listOf("Folk", "Country", "Rock"), found.map { it.label })
            assertEquals(
                listOf("mb-a", "mb-a", "mb-b").map(MusicBrainzLabels::artistPage),
                found.map { it.url },
            )
            assertEquals(listOf("mb-a", "mb-b"), asked)
        }

    @Test
    fun `an artist MusicBrainz does not know, or whose request failed, gives none`() =
        runTest {
            val labels = labels()
            val gone = labels.labels(listOf("mb-a", "mb-gone"))
            assertEquals(listOf("Folk", "Country"), gone.map { it.label })

            val down = labels.labels(listOf("mb-down", "mb-b"))
            assertEquals(listOf("Country", "Rock"), down.map { it.label })
        }

    @Test
    fun `an artist's tags are asked for once, for a second track too, but a failure is asked again`() =
        runTest {
            val labels = labels()
            labels.labels(listOf("mb-a", "mb-gone", "mb-down"))
            labels.labels(listOf("mb-a", "mb-gone", "mb-down"))
            assertEquals(listOf("mb-a", "mb-gone", "mb-down", "mb-down"), asked)

            tags["mb-down"] = Lookup.Found(listOf("jazz"))
            assertEquals(listOf("Jazz"), labels.labels(listOf("mb-down")).map { it.label })
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
    fun `a second track by the same artist asks nothing while the artist cache has its tags, after a restart too`() =
        runTest {
            val kept = MemoryArtistResults()
            val first = labels(kept = kept).labels(listOf("mb-a", "mb-gone", "mb-down"))
            assertEquals(listOf("mb-a", "mb-gone", "mb-down"), asked)
            assertEquals(setOf("mb-a", "mb-gone"), kept.keys(MusicBrainzLabels.PART), "a failure is not kept")

            // A new instance, as after a restart: nothing in memory, the artist cache kept.
            asked.clear()
            val again = labels(kept = kept).labels(listOf("mb-a", "mb-gone"))
            assertEquals(emptyList(), asked)
            assertEquals(first.map { it.label }, again.map { it.label })
        }

    @Test
    fun `tags past the artist cache's lifetime are asked for again, with no fresh lifetime in memory`() =
        runTest {
            val kept = MemoryArtistResults(clock, 1.days)
            val labels = labels(kept = kept)
            labels.labels(listOf("mb-a"))
            now += 1.days - 1.milliseconds
            labels.labels(listOf("mb-a"))
            assertEquals(listOf("mb-a"), asked)

            now += 1.milliseconds
            labels.labels(listOf("mb-a"))
            assertEquals(listOf("mb-a", "mb-a"), asked, "the same instance asks again once the kept tags expire")
        }

    @Test
    fun `tags in the artist cache that cannot be read are asked for again`() =
        runTest {
            val kept = MemoryArtistResults()
            kept.keep(MusicBrainzLabels.PART, "mb-a", "not json")

            assertEquals(listOf("Folk", "Country"), labels(kept = kept).labels(listOf("mb-a")).map { it.label })
            assertEquals(listOf("mb-a"), asked)
        }

    @Test
    fun `no artists, nothing asked`() =
        runTest {
            assertEquals(emptyList(), labels().labels(emptyList()))
            assertEquals(emptyList(), asked)
        }

    @Test
    fun `the artist's page is MusicBrainz's`() {
        assertEquals("https://musicbrainz.org/artist/mb-a", MusicBrainzLabels.artistPage("mb-a"))
    }
}
