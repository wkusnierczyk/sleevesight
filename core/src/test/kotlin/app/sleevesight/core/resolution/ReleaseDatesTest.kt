package app.sleevesight.core.resolution

import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.ReleaseDate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The releases of the track's recordings, and the year after the album chosen from them (#556), one
 * test per rule of `ReleaseDate.albumYear` (design, *Key data*).
 */
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
        lengthSeconds: Int?,
        vararg releases: MbRelease,
    ) = recording(mbid, "Wind of Change", artist, lengthSeconds, releases.mapNotNull { it.date }.minOrNull())
        .copy(releases = releases.toList())

    private fun searched(vararg recordings: MbRecording) = FakeMusicBrainz(Lookup.Found(recordings.toList()))

    private fun year(
        album: String,
        dates: List<ReleaseDate>,
        playerYear: String? = null,
    ) = ReleaseDate.albumYear(album, dates, playerYear, names::key)

    @Test
    fun `the releases of every recording the choice weighed give the dates, not only the accepted one's`() =
        runTest {
            val soundtrack = "Project Hail Mary (Original Motion Picture Soundtrack)"
            val musicBrainz =
                searched(
                    recording(
                        "original",
                        "Scorpions",
                        312,
                        release("crazy-world", "Crazy World", "1990-11-06"),
                        release("undated", "Undated Hits", null),
                    ),
                    // The soundtrack's own recording, released later: not the one accepted.
                    recording("soundtrack", "Scorpions", 313, release("phm", soundtrack, "2026-03-20")),
                    // Too long to be the track, and another artist's: neither gives a date.
                    recording("live", "Scorpions", 420, release("live", "Live Album", "1995")),
                    recording("cover", "Someone Else", 312, release("tribute", "Tribute", "2001")),
                )
            val resolution = resolve(musicBrainz, track("Wind of Change", "Scorpions"), 312)
            assertEquals("original", resolution.recordingMbid)
            assertEquals(
                listOf(
                    ReleaseDate("Crazy World", "1990-11-06", "group-crazy-world"),
                    ReleaseDate(soundtrack, "2026-03-20", "group-phm"),
                ),
                resolution.releaseDates,
            )
        }

    @Test
    fun `rule 1, a namesake's recording, left out of the choice by its length, gives no date`() =
        runTest {
            // The track's artist's recording, measured, and a namesake's, of no known length.
            val ours = recording("ours", "Artist", 200, release("hits-2020", "Hits", "2020"))
            val namesake =
                recording("namesake", "Artist", null, release("hits-1960", "Hits", "1960"))
                    .copy(credits = listOf(credit("Artist", "mbid-namesake")))
            val resolution = resolve(searched(ours, namesake), track("Wind of Change", "Artist"))
            assertEquals("ours", resolution.recordingMbid)
            assertEquals(listOf(ReleaseDate("Hits", "2020", "group-hits-2020")), resolution.releaseDates)
            assertEquals("2020", year("Hits", resolution.releaseDates))
        }

    @Test
    fun `rule 1, under an honorific variant, only the recordings the choice weighed give dates`() =
        runTest {
            val measured = recording("measured", "Mr. A Balladeer", 200, release("b-1950", "Ballads", "1950"))
            val unmeasured = recording("unmeasured", "Mr. A Balladeer", null, release("b-1940", "Ballads", "1940"))
            val resolution = resolve(searched(measured, unmeasured), track("Wind of Change", "A Balladeer"))
            assertEquals("measured", resolution.recordingMbid)
            assertEquals(listOf(ReleaseDate("Ballads", "1950", "group-b-1950")), resolution.releaseDates)
        }

    @Test
    fun `rule 1, a release is known by its own title, never its release group's`() =
        runTest {
            val edition = release("edition", "Album (Deluxe Edition)", "2005", group = "Album")
            val musicBrainz = searched(recording("song", "Artist", 200, edition))
            val resolution = resolve(musicBrainz, track("Wind of Change", "Artist"))
            val deluxe = ReleaseDate("Album (Deluxe Edition)", "2005", "group-edition")
            assertEquals(listOf(deluxe), resolution.releaseDates)
            // "Album" is the deluxe edition's release group, not a release of 2005: the player's year.
            assertEquals("1990", year("Album", resolution.releaseDates, playerYear = "1990"))
        }

    @Test
    fun `rule 1, a title is matched as sources write it differently, else without its edition`() {
        val dates =
            listOf(
                ReleaseDate("Abbey Road", "1969-09-26", "g-abbey"),
                ReleaseDate("Don’t Stop", "1977", "g-dont"),
                ReleaseDate("Let It Be… Naked", "2003", "g-naked"),
            )
        assertEquals("1977", year("Don't Stop", dates))
        assertEquals("1969", year("abbey road", dates))
        assertEquals("1969", year("Abbey Road (Remastered)", dates))
        assertEquals("1969", year("Abbey Road (Remastered 2019) [Super Deluxe]", dates))
        // Not a bracketed edition: a name of its own.
        assertNull(year("Let It Be", dates))
        // A release named with its edition is taken first.
        val remaster = dates + ReleaseDate("Abbey Road (Remastered)", "2009-09-09", "g-remaster")
        assertEquals("2009", year("Abbey Road (Remastered)", remaster))
    }

    @Test
    fun `rule 1, a title's years are one per release group, its earliest release in each`() {
        val releases =
            listOf(
                ReleaseDate("Crazy World", "2010-05-21", "g-crazy"),
                ReleaseDate("Crazy World", "1990-11-06", "g-crazy"),
                ReleaseDate("Love Songs", "2015", "g-love-2015"),
                ReleaseDate("love songs", "1985", "g-love-1985"),
            )
        val dates = ReleaseDate.collapse(releases, names::key)
        assertEquals(
            listOf(
                ReleaseDate("Crazy World", "1990-11-06", "g-crazy"),
                ReleaseDate("love songs", "1985", "g-love-1985"),
                ReleaseDate("Love Songs", "2015", "g-love-2015"),
            ),
            dates,
        )
        assertEquals(listOf("1990"), ReleaseDate.yearsOf("Crazy World", dates, names::key))
        assertEquals(listOf("1985", "2015"), ReleaseDate.yearsOf("Love Songs", dates, names::key))
    }

    private val loveSongs = listOf(ReleaseDate("Love Songs", "1985", "g-1"), ReleaseDate("Love Songs", "2015", "g-2"))

    @Test
    fun `rule 2, the player's year wins when it is one of the named releases' years`() {
        assertEquals("2015", year("Love Songs", loveSongs, playerYear = "2015"))
        assertEquals("1985", year("Love Songs", loveSongs, playerYear = "1985"))
    }

    @Test
    fun `rule 3, one year is the album's, whatever the player says`() {
        val dates = listOf(ReleaseDate("Crazy World", "1990-11-06", "g-crazy"))
        assertEquals("1990", year("Crazy World", dates))
        assertEquals("1990", year("Crazy World", dates, playerYear = "2010"))
    }

    @Test
    fun `rule 4, with no release named, the player's year, else none`() {
        val dates = listOf(ReleaseDate("Crazy World", "1990-11-06", "g-crazy"))
        assertEquals("2003", year("Rock Ballads Forever", dates, playerYear = "2003"))
        assertNull(year("Rock Ballads Forever", dates))
        assertNull(year("…", dates))
        assertNull(year("Crazy World", emptyList()))
    }

    @Test
    fun `rule 5, several years and none the player's, no year`() {
        assertNull(year("Love Songs", loveSongs))
        assertNull(year("Love Songs", loveSongs, playerYear = "2000"))
    }

    @Test
    fun `a year is the four digits a date begins with`() {
        assertEquals("1974", ReleaseDate.yearIn("1974-03-25"))
        assertEquals("1974", ReleaseDate.yearIn("1974"))
        assertNull(ReleaseDate.yearIn("19"))
        assertNull(ReleaseDate.yearIn("19xx"))
    }
}
