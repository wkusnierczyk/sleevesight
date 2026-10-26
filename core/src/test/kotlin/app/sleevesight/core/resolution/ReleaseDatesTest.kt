package app.sleevesight.core.resolution

import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.ReleaseDate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The dates of the titles the track's releases go by, for the year after the album (#556). */
class ReleaseDatesTest {
    private val names = NameMatcher(JvmAccentFolder)

    private fun release(
        mbid: String,
        title: String,
        date: String?,
        group: String = title,
    ) = MbRelease(mbid, title, date, MbReleaseGroup("group-$mbid", group, "Album"))

    private fun recording(
        mbid: String,
        artist: String,
        lengthSeconds: Int,
        vararg releases: MbRelease,
    ) = recording(mbid, "Wind of Change", artist, lengthSeconds, releases.mapNotNull { it.date }.minOrNull())
        .copy(releases = releases.toList())

    @Test
    fun `the releases of every recording that fits give the dates, not only the accepted one's`() =
        runTest {
            val search =
                listOf(
                    recording(
                        "original",
                        "Scorpions",
                        312,
                        release("crazy-world", "Crazy World", "1990-11-06"),
                        release("reissue", "Crazy World", "2010-05-21"),
                        release("undated", "Undated Hits", null),
                    ),
                    // The soundtrack's own recording, released later: not the one accepted.
                    recording(
                        "soundtrack",
                        "Scorpions",
                        313,
                        release("phm", "Project Hail Mary (Original Motion Picture Soundtrack)", "2026-03-20"),
                    ),
                    // Too long to be the track, and another artist's: neither gives a date.
                    recording("live", "Scorpions", 420, release("live", "Live Album", "1995")),
                    recording("cover", "Someone Else", 312, release("tribute", "Tribute", "2001")),
                )
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(search)), track("Wind of Change", "Scorpions"), 312)
            assertEquals("original", resolution.recordingMbid)
            assertEquals("1990-11-06", resolution.firstReleaseDate)
            assertEquals(
                listOf(
                    ReleaseDate("Crazy World", "1990-11-06"),
                    ReleaseDate("Project Hail Mary (Original Motion Picture Soundtrack)", "2026-03-20"),
                ),
                resolution.releaseDates,
            )
        }

    @Test
    fun `a release group's title counts beside the release's own`() =
        runTest {
            val edition = release("edition", "Album (Deluxe Edition)", "2005", group = "Album")
            val search = listOf(recording("song", "Artist", 200, edition))
            val resolution = resolve(FakeMusicBrainz(Lookup.Found(search)), track("Wind of Change", "Artist"))
            assertEquals(
                listOf(ReleaseDate("Album", "2005"), ReleaseDate("Album (Deluxe Edition)", "2005")),
                resolution.releaseDates,
            )
        }

    @Test
    fun `the album's year is found by its title as sources write it differently`() {
        val soundtrack = "Project Hail Mary (Original Motion Picture Soundtrack)"
        val dates =
            listOf(
                ReleaseDate(soundtrack, "2026-03-20"),
                ReleaseDate("Crazy World", "1990-11-06"),
                ReleaseDate("Don’t Stop", "1977"),
            )
        assertEquals("2026", ReleaseDate.yearOf(soundtrack, dates, names::key))
        assertEquals("1990", ReleaseDate.yearOf("crazy world", dates, names::key))
        assertEquals("1977", ReleaseDate.yearOf("Don't Stop", dates, names::key))
    }

    @Test
    fun `an album the releases do not name has no year, an edition's included`() {
        val dates = listOf(ReleaseDate("Crazy World", "1990-11-06"))
        // Not given the original's year for an edition MusicBrainz does not name.
        assertNull(ReleaseDate.yearOf("Crazy World (Remastered 2009)", dates, names::key))
        assertNull(ReleaseDate.yearOf("Project Hail Mary", dates, names::key))
        assertNull(ReleaseDate.yearOf("…", dates, names::key))
        assertNull(ReleaseDate.yearOf("Crazy World", emptyList(), names::key))
    }

    @Test
    fun `a year is the four digits a date begins with`() {
        assertEquals("1974", ReleaseDate.yearIn("1974-03-25"))
        assertEquals("1974", ReleaseDate.yearIn("1974"))
        assertNull(ReleaseDate.yearIn("19"))
        assertNull(ReleaseDate.yearIn("19xx"))
    }
}
