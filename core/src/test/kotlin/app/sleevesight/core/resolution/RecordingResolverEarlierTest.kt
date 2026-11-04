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

    @Test
    fun `with the player's length, a date only lengthless recordings give leaves the choice provisional`() =
        runTest {
            // Nothing on the page with a length fits: the lengthless 1970 one gives the date, which may
            // be too early for a later one that would be preferred.
            val page =
                (1 until MusicBrainzLookup.SEARCH_PAGE).map { live(it) } +
                    recording("demo", "Dream On", "Aerosmith", null, "1970")
            val musicBrainz = musicBrainz(page = page, before = Lookup.Found(listOf(page.last())))
            val resolution = resolve(musicBrainz, track("Dream On", "Aerosmith"), 267)
            assertEquals("demo", resolution.recordingMbid)
            assertEquals(listOf("1970"), musicBrainz.beforeAsked)
            assertTrue(resolution.provisional)
        }

    @Test
    fun `a full search by date is followed by one by its own earliest date, up to three`() =
        runTest {
            // Hundreds of live releases: the search by 1978-10 is full, its earliest fit 1975; the
            // search by 1975 holds the studio recording.
            val byLive =
                listOf(recording("take-75", "Dream On", "Aerosmith", 266, "1975")) +
                    (1 until MusicBrainzLookup.SEARCH_PAGE).map { live(it) }
            val musicBrainz = musicBrainz()
            musicBrainz.beforeAnswers += Lookup.Found(byLive)
            musicBrainz.before = Lookup.Found(listOf(studio))
            val resolution = resolve(musicBrainz, track("Dream On", "Aerosmith"), 267)
            assertEquals("studio", resolution.recordingMbid)
            assertEquals(listOf("1978-10", "1975"), musicBrainz.beforeAsked)
            assertFalse(resolution.provisional)

            val always = musicBrainz(before = Lookup.Found(byLive))
            val capped = resolve(always, track("Dream On", "Aerosmith"), 267)
            assertEquals(3, always.beforeAsked.size)
            assertTrue(capped.provisional)
        }

    @Test
    fun `a date from lengthless recordings is enough when the search finds one with a length`() =
        runTest {
            val page =
                (1 until MusicBrainzLookup.SEARCH_PAGE).map { live(it) } +
                    recording("demo", "Dream On", "Aerosmith", null, "1970")
            val musicBrainz =
                musicBrainz(
                    page = page,
                    before =
                        Lookup.Found(
                            listOf(page.last(), recording("early", "Dream On", "Aerosmith", 267, "1969")),
                        ),
                )
            val resolution = resolve(musicBrainz, track("Dream On", "Aerosmith"), 267)
            assertEquals("early", resolution.recordingMbid)
            assertFalse(resolution.provisional)
        }

    // A live take too long to fit.
    private fun live(n: Int) = recording("live-$n", "Dream On", "Aerosmith", 300 + n, "1970")
}
