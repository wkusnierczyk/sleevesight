// Lengths and dates are the data here, not magic numbers.
@file:Suppress("MagicNumber")

package app.sleevesight.core.resolution

import app.sleevesight.core.model.Outcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A full first page (#906): the recordings released by its earliest fitting one are searched for,
 * since the earliest recording may be past the page.
 */
class RecordingResolverEarlierTest {
    // Dream On, Aerosmith, shaped as MusicBrainz answered once: a full page of live takes and later
    // releases, the earliest that fits a 1978 live one; the 1973 studio recording past the page.
    private val fitting =
        listOf(
            recording("bootleg", "Dream On", "Aerosmith", 268, "1978-10"),
            recording("mix", "Dream On", "Aerosmith", 268, "2026-03-20"),
        )
    private val fullPage =
        fitting +
            (1..MusicBrainzLookup.SEARCH_PAGE - fitting.size).map {
                recording("live-$it", "Dream On", "Aerosmith", 300 + it, "1970")
            }
    private val studio = recording("studio", "Dream On", "Aerosmith", 267, "1973-01-05")

    private fun musicBrainz(
        page: List<MbRecording> = fullPage,
        before: Lookup<List<MbRecording>> = Lookup.Found(listOf(studio, fitting.first())),
    ) = FakeMusicBrainz(Lookup.Found(page)).apply { this.before = before }

    @Test
    fun `a studio track past a full first page is found by the earliest fitting one's date`() =
        runTest {
            val musicBrainz = musicBrainz()
            val resolution = resolve(musicBrainz, track("Dream On", "Aerosmith"), 267)
            assertEquals(Outcome.SongArticle, resolution.outcome)
            assertEquals("studio", resolution.recordingMbid)
            assertEquals("1973-01-05", resolution.firstReleaseDate)
            // The 1970 takes are too long to fit, so the date is the live take's.
            assertEquals(listOf("1978-10"), musicBrainz.beforeAsked)
            assertFalse(resolution.provisional)
        }

    @Test
    fun `a first page that is not full holds every recording, so nothing more is asked`() =
        runTest {
            val musicBrainz = musicBrainz(page = fitting)
            assertEquals("bootleg", resolve(musicBrainz, track("Dream On", "Aerosmith"), 267).recordingMbid)
            assertEquals(emptyList(), musicBrainz.beforeAsked)
        }

    @Test
    fun `when that search fails, the first page decides, provisionally`() =
        runTest {
            val resolution =
                resolve(musicBrainz(before = Lookup.Failed("RateLimited")), track("Dream On", "Aerosmith"), 267)
            assertEquals("bootleg", resolution.recordingMbid)
            assertTrue(resolution.provisional)
        }

    @Test
    fun `a full page with nothing that fits and is dated has nothing to search by, so finds nothing provisionally`() =
        runTest {
            val page = (1..MusicBrainzLookup.SEARCH_PAGE).map { live(it) }
            val musicBrainz = musicBrainz(page = page)
            val resolution = resolve(musicBrainz, track("Dream On", "Aerosmith"), 267)
            assertEquals(Outcome.None, resolution.outcome)
            assertTrue(resolution.provisional)
            assertEquals(emptyList(), musicBrainz.beforeAsked)
        }

    @Test
    fun `with the player's length, a recording with none does not set the date`() =
        runTest {
            // A lengthless 1970 recording fits, but the choice prefers one whose length is known.
            val page = fullPage.dropLast(1) + recording("demo", "Dream On", "Aerosmith", null, "1970")
            val musicBrainz = musicBrainz(page = page)
            assertEquals("studio", resolve(musicBrainz, track("Dream On", "Aerosmith"), 267).recordingMbid)
            assertEquals(listOf("1978-10"), musicBrainz.beforeAsked)
        }

    @Test
    fun `a search by date that is itself full gives a provisional choice`() =
        runTest {
            val full = listOf(studio) + (1 until MusicBrainzLookup.SEARCH_PAGE).map { live(it) }
            val resolution = resolve(musicBrainz(before = Lookup.Found(full)), track("Dream On", "Aerosmith"), 267)
            assertEquals("studio", resolution.recordingMbid)
            assertTrue(resolution.provisional)
        }

    // A live take too long to fit.
    private fun live(n: Int) = recording("live-$n", "Dream On", "Aerosmith", 300 + n, "1970")
}
