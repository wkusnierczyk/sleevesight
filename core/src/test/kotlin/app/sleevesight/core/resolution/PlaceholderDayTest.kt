package app.sleevesight.core.resolution

import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.PlaceholderDay
import app.sleevesight.core.sources.ReleaseDate
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MusicBrainz's January 1 said as its year alone when a later day of that year is known for the same
 * release group (#1203): *A/B* (KALEO) is dated `2016-01-01` by an Icelandic
 * digital release, and `2016-06-10` everywhere else. The date never moves to the later release.
 */
class PlaceholderDayTest {
    private val names = NameMatcher(JvmAccentFolder)

    private fun collapse(vararg releases: ReleaseDate) = ReleaseDate.collapse(releases.toList(), names::key)

    @Test
    fun `a January 1 beside a later day of the year is said as the year`() {
        assertEquals("2016", PlaceholderDay.said("2016-01-01", listOf("2016-01-01", "2016-06-10")))
        assertEquals("2016", PlaceholderDay.said("2016-01-01", listOf(null, "2016-12-31")))
    }

    @Test
    fun `a January 1 with no later day of the year keeps its day`() {
        // Alone, beside other years, and beside a year or month alone, which say nothing of the day.
        assertEquals("2016-01-01", PlaceholderDay.said("2016-01-01", listOf("2016-01-01")))
        assertEquals("2016-01-01", PlaceholderDay.said("2016-01-01", listOf("2015-06-10", "2017-06-10", null)))
        assertEquals("2016-01-01", PlaceholderDay.said("2016-01-01", listOf("2016", "2016-06")))
    }

    @Test
    fun `any other day is said as given`() {
        assertEquals("2016-01-02", PlaceholderDay.said("2016-01-02", listOf("2016-06-10")))
        assertEquals("2016-06-01", PlaceholderDay.said("2016-06-01", listOf("2016-06-10")))
        assertEquals("2016-01", PlaceholderDay.said("2016-01", listOf("2016-06-10")))
        assertEquals("2016", PlaceholderDay.said("2016", listOf("2016-06-10")))
    }

    @Test
    fun `a group's January 1 is said as its releases are kept`() {
        // *A/B*'s group, its first release kept as 2016 for a placeholder day; *New Year*'s, kept to the day.
        val ab = ReleaseDate("A/B", "2016", "g-ab", "Album", placeholderDay = true)
        val deluxe = ReleaseDate("A/B (Deluxe Edition)", "2026-06-10", "g-ab", "Album")
        assertEquals("2016", PlaceholderDay.asKept("2016-01-01", listOf(ab, deluxe)))
        val newYear = ReleaseDate("New Year", "2000-01-01", "g-ny", "Album")
        assertEquals("2000-01-01", PlaceholderDay.asKept("2000-01-01", listOf(newYear)))
        assertEquals("2000-01-01", PlaceholderDay.asKept("2000-01-01", listOf(newYear.copy(date = "2001"))))
        assertEquals(
            "1974-01",
            PlaceholderDay.asKept("1974-01", listOf(newYear.copy(date = "1974", placeholderDay = true))),
        )
    }

    @Test
    fun `a year MusicBrainz gives alone is no sign that a group's January 1 is a placeholder`() {
        // The group's releases: `2000-01-01`, `2000`, `2020-05-04`. Nothing in 2000 is out on a later
        // day, so the January 1 keeps its day, as the album's own sentence keeps it.
        val kept =
            collapse(
                ReleaseDate("New Year", "2000-01-01", "g-ny", "Album"),
                ReleaseDate("New Year (Promo)", "2000", "g-ny", "Album"),
                ReleaseDate("New Year (Deluxe Edition)", "2020-05-04", "g-ny", "Album"),
            )
        assertEquals(listOf(false, false, false), kept.map { it.placeholderDay })
        assertEquals("2000-01-01", PlaceholderDay.asKept("2000-01-01", kept))
    }

    @Test
    fun `under one title, a day is earlier than the year alone it is in`() {
        // As MusicBrainz dates the group's first release: `2000-01-01`, not `2000`.
        val kept =
            collapse(
                ReleaseDate("New Year", "2000", "g-ny", "Album"),
                ReleaseDate("New Year", "2000-01-01", "g-ny", "Album"),
                ReleaseDate("New Year", "2000-03", "g-ny", "Album"),
            )
        assertEquals(listOf(ReleaseDate("New Year", "2000-01-01", "g-ny", "Album")), kept)
    }

    @Test
    fun `A-B's album, first out on 1 January 2016 and on 10 June 2016 elsewhere, is said as 2016`() {
        val kept =
            collapse(
                ReleaseDate("A/B", "2016-06-10", "g-ab", "Album"),
                ReleaseDate("A/B", "2016-01-01", "g-ab", "Album"),
                ReleaseDate("A/B", "2016-06-10", "g-ab", "Album"),
                ReleaseDate("A/B (Deluxe Edition)", "2026-06-10", "g-ab", "Album"),
            )
        // The year of the Icelandic release, never the June day of the later ones.
        assertEquals(
            listOf(
                ReleaseDate("A/B", "2016", "g-ab", "Album", placeholderDay = true),
                ReleaseDate("A/B (Deluxe Edition)", "2026-06-10", "g-ab", "Album"),
            ),
            kept,
        )
    }

    @Test
    fun `a later day under another title of the same release group counts, one in another group does not`() {
        val kept =
            collapse(
                ReleaseDate("Album", "2016-01-01", "g-album", "Album"),
                ReleaseDate("Album (Japan Edition)", "2016-03-02", "g-album", "Album"),
                ReleaseDate("Other", "2017-01-01", "g-other", "Album"),
                ReleaseDate("Compilation", "2017-05-05", "g-comp", "Album"),
            )
        assertEquals(
            mapOf(
                "Album" to "2016",
                "Album (Japan Edition)" to "2016-03-02",
                "Compilation" to "2017-05-05",
                "Other" to "2017-01-01",
            ),
            kept.associate { it.title to it.date },
        )
    }

    @Test
    fun `an album truly out on January 1, with no later release that year, keeps its day`() {
        // Reissued the next year: nothing in its own year says the day is a placeholder.
        val kept =
            collapse(
                ReleaseDate("New Year", "2000-01-01", "g-ny", "Album"),
                ReleaseDate("New Year", "2001-05-04", "g-ny", "Album"),
            )
        assertEquals(listOf(ReleaseDate("New Year", "2000-01-01", "g-ny", "Album")), kept)
    }

    @Test
    fun `a track's first release on January 1, a later release of its group that year, is said as the year`() =
        runTest {
            val early = MbRelease("r-is", "A/B", "2016-01-01", MbReleaseGroup("g-ab", "A/B", "Album"))
            val later = MbRelease("r-us", "A/B", "2016-06-10", MbReleaseGroup("g-ab", "A/B", "Album"))
            val hits = MbRelease("r-hits", "Hits 2016", "2016-11-18", MbReleaseGroup("g-hits", "Hits 2016", "Album"))
            val song =
                recording("song", "Way Down We Go", "KALEO", 219, "2016-01-01")
                    .copy(releases = listOf(later, hits, early))
            val searched = FakeMusicBrainz(Lookup.Found(listOf(song)))
            val first = resolve(searched, track("Way Down We Go", "KALEO"), 219).firstRelease
            // The release dated 1 January stays the first release: only its date is said as the year.
            // The compilation, in another group, counts for nothing.
            assertEquals("2016", first?.date)
            assertEquals("r-is", first?.releaseMbid)
            assertEquals("g-ab", first?.releaseGroupMbid)
        }

    @Test
    fun `a track's first release on January 1, the recording's only release that year, keeps its day`() =
        runTest {
            val early = MbRelease("r-ny", "New Year", "2000-01-01", MbReleaseGroup("g-ny", "New Year", "Album"))
            val reissue = MbRelease("r-re", "New Year", "2001-05-04", MbReleaseGroup("g-ny", "New Year", "Album"))
            val song =
                recording("song", "Auld Lang Syne", "Artist", 200, "2000-01-01").copy(releases = listOf(reissue, early))
            val searched = FakeMusicBrainz(Lookup.Found(listOf(song)))
            val first = resolve(searched, track("Auld Lang Syne", "Artist")).firstRelease
            assertEquals("2000-01-01", first?.date)
            assertEquals("r-ny", first?.releaseMbid)
        }

    // What each place says of one release group's first release ([group], dated [first], as
    // MusicBrainz dates the group and the chosen recording), for a track whose search finds
    // [recordings], each a recording's releases, the first the chosen one (dated [first]): the album's
    // own sentence (its title's release as kept), an edition's sentence (the group's date as kept), the
    // track's first release, and the album insight's line (the group's date beside the dates its lookup
    // lists: every release of the group, of whichever recording).
    private suspend fun everywhere(
        first: String,
        group: String,
        vararg recordings: List<MbRelease>,
    ): List<String?> {
        val songs =
            recordings.mapIndexed { i, releases ->
                val dated = releases.mapNotNull { it.date }.minBy { ReleaseDate.byPrecision(it) }
                recording("song-$i", "Song", "Artist", 200, if (i == 0) first else dated).copy(releases = releases)
            }
        val resolution = resolve(FakeMusicBrainz(Lookup.Found(songs)), track("Song", "Artist"))
        val inGroup = recordings.flatMap { it }.filter { it.releaseGroup?.mbid == group }
        val original = recordings.first().first { it.date == first }.title
        val kept = resolution.releaseDates.filter { it.group == group }
        return listOf(
            kept.single { it.title == original }.date,
            PlaceholderDay.asKept(first, kept),
            resolution.firstRelease?.date,
            PlaceholderDay.said(first, inGroup.map { it.date }),
        )
    }

    private suspend fun everywhere(
        first: String,
        vararg releases: MbRelease,
    ): List<String?> = everywhere(first, "g", releases.toList())

    private fun release(
        id: String,
        title: String,
        date: String,
        group: String = "g",
    ) = MbRelease(id, title, date, MbReleaseGroup(group, title, "Album"))

    @Test
    fun `every place says a group's first release the same`() =
        runTest {
            // *A/B*: a placeholder day.
            assertEquals(
                List(4) { "2016" },
                everywhere(
                    "2016-01-01",
                    release("r-is", "A/B", "2016-01-01"),
                    release("r-us", "A/B", "2016-06-10"),
                    release("r-dx", "A/B (Deluxe Edition)", "2026-06-10"),
                ),
            )
            // Truly out on January 1, reissued later.
            assertEquals(
                List(4) { "2000-01-01" },
                everywhere(
                    "2000-01-01",
                    release("r-ny", "New Year", "2000-01-01"),
                    release("r-re", "New Year", "2001-05-04"),
                ),
            )
            // A year alone beside it, under another title and under its own: no sign of a placeholder.
            for (promo in listOf("New Year (Promo)", "New Year")) {
                assertEquals(
                    List(4) { "2000-01-01" },
                    everywhere(
                        "2000-01-01",
                        release("r-ny", "New Year", "2000-01-01"),
                        release("r-promo", promo, "2000"),
                        release("r-dx", "New Year (Deluxe Edition)", "2020-05-04"),
                    ),
                )
            }
            // A single alone in its group, also on a compilation of another group later that year:
            // the compilation is no sign of the single's day.
            assertEquals(
                List(4) { "2016-01-01" },
                everywhere(
                    "2016-01-01",
                    "g-s",
                    listOf(
                        release("r-s", "S", "2016-01-01", "g-s"),
                        release("r-hits", "Hits 2016", "2016-11-18", "g-hits"),
                    ),
                ),
            )
            // The chosen recording only on *A/B*'s Icelandic release; another recording in the pool on
            // the group's later releases that year.
            assertEquals(
                List(4) { "2016" },
                everywhere(
                    "2016-01-01",
                    "g",
                    listOf(release("r-is", "A/B", "2016-01-01")),
                    listOf(release("r-us", "A/B", "2016-06-10"), release("r-uk", "A/B", "2016-06-10")),
                ),
            )
        }
}
