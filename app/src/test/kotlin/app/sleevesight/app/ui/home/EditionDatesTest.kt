package app.sleevesight.app.ui.home

import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.normalizer.YearTag
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.resolution.MbGroupFirst
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.synthesis.ReleaseType
import app.sleevesight.core.synthesis.Released
import app.sleevesight.core.synthesis.SearchAnswer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * *Dates* for a remaster or an edition the player's album names with a year (#1116), and the album
 * line's year beside such a name; the other edition cases are `HomeDatesTest`'s.
 */
class EditionDatesTest {
    private val girlName = "Material Girl (2024 Remaster)"

    // *Material Girl* from the single *Material Girl (2024 Remaster)*, which MusicBrainz lacks: the
    // name matches the 1985 single.
    private val girl = sundown.copy(title = "Material Girl", artists = "Madonna", album = girlName, year = 2024)

    private val girlDates = listOf(ReleaseDate("Material Girl", "1985", "g-girl", "Single"))

    private val girlGroup = mapOf("g-girl" to MbGroupFirst("g-girl", "Material Girl", "1985"))

    private val likeAVirgin = FirstRelease("1984-11-13", "Like a Virgin", "g-virgin", "r-virgin", "Album")

    private val remaster2024 = EditionPart.Its(YearTag("2024", true, "Material Girl"))

    // [playing]'s state with a run's resolution: its [first] release and its releases' [dates].
    private fun resolved(
        playing: NowPlaying,
        first: FirstRelease?,
        dates: List<ReleaseDate>,
    ): TrackState = track(playing).copy(resolution = Resolution(firstRelease = first, releaseDates = dates))

    @Test
    fun aRemasterMusicBrainzLacksSaysTheOriginalThenThisIsItsRemaster() {
        val expected =
            listOf(
                DateSentence.Edition("1985", "Material Girl", remaster2024, ReleaseKind.SINGLE),
                DateSentence.First("1984-11-13", "Like a Virgin", null, album = true),
            )
        assertEquals(expected, homeDates(resolved(girl, likeAVirgin, girlDates), girl, girlGroup))
        // Before the group's lookup answers, the same, by the release's title.
        assertEquals(expected, homeDates(resolved(girl, likeAVirgin, girlDates), girl))
        // The album line shows no year: the name gives the remaster's.
        assertEquals(HeaderYears(album = "1985"), headerYears(resolved(girl, likeAVirgin, girlDates), girl))
        assertEquals(null, albumLineYear(girlName, "1985"))
    }

    @Test
    fun aTrackFirstOutOnTheRemasteredSingleItselfSaysTheOriginalSingle() {
        // "This single" would point at the 2024 remaster being played: "the original single".
        val single = FirstRelease("1985", "Material Girl", "g-girl", "r-girl", "Single")
        val edition = DateSentence.Edition("1985", "Material Girl", remaster2024, ReleaseKind.SINGLE)
        assertEquals(
            listOf(edition, DateSentence.FirstOriginal("Material Girl", ReleaseKind.SINGLE)),
            homeDates(resolved(girl, single, girlDates), girl, girlGroup),
        )
        // Before the group's lookup answers, the track's own date and title.
        assertEquals(
            listOf(edition, DateSentence.First("1985", "Material Girl", ReleaseKind.SINGLE)),
            homeDates(resolved(girl, single, girlDates), girl),
        )
    }

    @Test
    fun aRemasteredAlbumMusicBrainzLacksNamesTheOriginalAlbumForTheTrack() {
        val virgin = girl.copy(title = "Like a Virgin", album = "Like a Virgin (2024 Remaster)")
        val dates = listOf(ReleaseDate("Like a Virgin", "1984-11-13", "g-virgin", "Album"))
        val edition =
            DateSentence.Edition("1984-11-13", "Like a Virgin", EditionPart.Its(YearTag("2024", true, "Like a Virgin")))
        val group = mapOf("g-virgin" to MbGroupFirst("g-virgin", "Like a Virgin", "1984-11-13"))
        assertEquals(
            listOf(edition, DateSentence.FirstOriginal("Like a Virgin")),
            homeDates(resolved(virgin, likeAVirgin, dates), virgin, group),
        )
        // Before the group's lookup answers, or offline, the original is only the release matched:
        // the track keeps its own date and title.
        assertEquals(
            listOf(edition, DateSentence.First("1984-11-13", "Like a Virgin", null, album = true)),
            homeDates(resolved(virgin, likeAVirgin, dates), virgin),
        )
    }

    @Test
    fun beforeTheLookupTheReleaseMatchedIsTheOriginalThenTheGroupsFirstOnceItAnswers() {
        // A bonus track on *X (2018 Remaster)*; MusicBrainz lists only the 1997 reissue *X* among
        // the track's releases, in the group of *X*, first released in March 1959.
        val bonus = sundown.copy(title = "Outtake", artists = "Someone", album = "X (2018 Remaster)", year = 2018)
        val first = FirstRelease("1997", "X", "g-x", "r-x", "Album")
        val state = resolved(bonus, first, listOf(ReleaseDate("X", "1997", "g-x", "Album")))
        val remaster = EditionPart.Its(YearTag("2018", true, "X"))
        val track = DateSentence.First("1997", "X", null, album = true)
        // Pending or offline: "The album X was first released in 1997. This is its 2018 remaster."
        assertEquals(listOf(DateSentence.Edition("1997", "X", remaster), track), homeDates(state, bonus))
        // The lookup answers: the album's first release is 1959's, and the track's still 1997's.
        val group = mapOf("g-x" to MbGroupFirst("g-x", "X", "1959-03"))
        assertEquals(listOf(DateSentence.Edition("1959-03", "X", remaster), track), homeDates(state, bonus, group))
    }

    @Test
    fun aGroupFirstReleasedEarlierInTheSameYearGivesTheOriginalItsDate() {
        // *X (2018 Remaster)*, matched to *X* of 20 November 1997, where the track first came out;
        // the group's first release is of 10 January 1997, under the same title.
        val track = sundown.copy(title = "Outtake", artists = "Someone", album = "X (2018 Remaster)", year = 2018)
        val first = FirstRelease("1997-11-20", "X", "g-x", "r-x", "Album")
        val state = resolved(track, first, listOf(ReleaseDate("X", "1997-11-20", "g-x", "Album")))
        val remaster = EditionPart.Its(YearTag("2018", true, "X"))
        val dated = DateSentence.First("1997-11-20", "X", null, album = true)
        // Pending: the release matched as the original, the track with its own date.
        assertEquals(listOf(DateSentence.Edition("1997-11-20", "X", remaster), dated), homeDates(state, track))
        // Answered: the original is January's, and the track, out in November, keeps its date.
        val group = mapOf("g-x" to MbGroupFirst("g-x", "X", "1997-01-10"))
        assertEquals(listOf(DateSentence.Edition("1997-01-10", "X", remaster), dated), homeDates(state, track, group))
        // Answered with the matched release's own date, or only its year: the original album.
        for (answer in listOf("1997-11-20", "1997")) {
            val same = mapOf("g-x" to MbGroupFirst("g-x", "X", answer))
            assertEquals(
                answer,
                listOf(DateSentence.Edition(answer, "X", remaster), DateSentence.FirstOriginal("X")),
                homeDates(state, track, same),
            )
        }
    }

    @Test
    fun aTrackFirstOutLaterInTheAlbumsYearIsNotThisAlbum() {
        // The album played, *X* of 10 January 2020; the track first out on *X (Expanded)*, in the
        // same group, on 20 November 2020: not "this album".
        val bonus = sundown.copy(title = "Bonus", artists = "Someone", album = "X", year = 2020)
        val dates =
            listOf(
                ReleaseDate("X", "2020-01-10", "g-x", "Album"),
                ReleaseDate("X (Expanded)", "2020-11-20", "g-x", "Album"),
            )
        val expanded = FirstRelease("2020-11-20", "X (Expanded)", "g-x", "r-x-expanded", "Album")
        val later = DateSentence.First("2020-11-20", "X (Expanded)", null, album = true)
        val state = resolved(bonus, expanded, dates)
        assertEquals(listOf(DateSentence.Album("2020-01-10"), later), homeDates(state, bonus))
        // On the album's date, or one less precise: this album.
        for (date in listOf("2020-01-10", "2020-01", "2020")) {
            val here = FirstRelease(date, "X", "g-x", "r-x", "Album")
            assertEquals(date, DateSentence.FirstHere(), homeDates(resolved(bonus, here, dates), bonus)[1])
        }
        // The same for an AI answer's dates.
        val answer = SearchAnswer.Dates(album = "2020-01-10", first = Released("2020-11-20", "X", ReleaseType.ALBUM))
        assertEquals(DateSentence.First("2020-11-20", "X", null, album = true), aiDates(answer, bonus)[1])
        val sameDay = answer.copy(first = Released("2020-01", "X", ReleaseType.ALBUM))
        assertEquals(DateSentence.FirstHere(), aiDates(sameDay, bonus)[1])
    }

    @Test
    fun aRemasterOfAnAlbumWithEditionsMusicBrainzDatesIsItsRemaster() {
        // *Rumours (2018 Remaster)*: its name matches *Rumours* alone, not the 2004 *Rumours
        // (Remastered)*, so the album's first release is the original, and this its 2018 remaster.
        val dreams =
            sundown.copy(title = "Dreams", artists = "Fleetwood Mac", album = "Rumours (2018 Remaster)", year = 2018)
        val rumours = FirstRelease("1977-02-04", "Rumours", "g-rumours", "r-rumours", "Album")
        val dates =
            listOf(
                ReleaseDate("Rumours", "1977-02-04", "g-rumours", "Album"),
                ReleaseDate("Rumours (Remastered)", "2004-03-22", "g-rumours", "Album"),
            )
        val group = mapOf("g-rumours" to MbGroupFirst("g-rumours", "Rumours", "1977-02-04"))
        assertEquals(
            listOf(
                DateSentence.Edition("1977-02-04", "Rumours", EditionPart.Its(YearTag("2018", true, "Rumours"))),
                DateSentence.FirstOriginal("Rumours"),
            ),
            homeDates(resolved(dreams, rumours, dates), dreams, group),
        )
    }

    @Test
    fun aRemasterLaterThanTheEditionMusicBrainzDatesUnderAnotherTitleNamesThatEdition() {
        // #1116's *Actually: Further Listening 1987 - 1988 (2018 Remaster)*: MusicBrainz dates the
        // edition from 2001, in *Actually*'s group, first released in 1987.
        val sin =
            sundown.copy(
                title = "It’s a Sin",
                artists = "Pet Shop Boys",
                album = "Actually: Further Listening 1987 - 1988 (2018 Remaster)",
                year = 2001,
            )
        val dates =
            listOf(
                ReleaseDate("Actually", "1987-09-07", "g-actually", "Album"),
                ReleaseDate("Actually / Further Listening 1987–1988", "2001-06-04", "g-actually", "Album"),
            )
        val group = mapOf("g-actually" to MbGroupFirst("g-actually", "Actually", "1987-09-07"))
        val name = "Actually: Further Listening 1987 - 1988"
        val actually = FirstRelease("1987-09-07", "Actually", "g-actually", "r-actually", "Album")
        assertEquals(
            listOf(
                DateSentence.Edition(
                    "1987-09-07",
                    "Actually",
                    EditionPart.Remaster(YearTag("2018", true, name), name, "2001-06-04"),
                ),
                DateSentence.FirstOriginal("Actually"),
            ),
            homeDates(resolved(sin, actually, dates), sin, group),
        )
    }

    @Test
    fun aRemasterOfItsOwnYearOrNoneIsTheOriginalAsBefore() {
        // *Material Girl (1985 Remaster)*, of the single's own year: no remaster said.
        val own = girl.copy(album = "Material Girl (1985 Remaster)", year = 1985)
        assertEquals(
            DateSentence.Album("1985", ReleaseKind.SINGLE),
            homeDates(resolved(own, likeAVirgin, girlDates), own, girlGroup).first(),
        )
    }

    @Test
    fun aQualifiedEditionMusicBrainzLacksIsItsEdition() {
        // *Sundown (2011 Anniversary Edition)* and *Sundown (Deluxe Edition 2011)*, matched to the
        // 1974 album: "This is its 2011 edition."
        val dates = listOf(ReleaseDate("Sundown", "1974-01", "g-sundown", "Album"))
        val first = FirstRelease("1974-01", "Sundown", "g-sundown", "r-sundown", "Album")
        val group = mapOf("g-sundown" to MbGroupFirst("g-sundown", "Sundown", "1974-01"))
        for (album in listOf("Sundown (2011 Anniversary Edition)", "Sundown (Deluxe Edition 2011)")) {
            val playing = sundown.copy(album = album, year = 2011)
            assertEquals(
                album,
                listOf(
                    DateSentence.Edition("1974-01", "Sundown", EditionPart.Its(YearTag("2011", false, "Sundown"))),
                    DateSentence.FirstOriginal("Sundown"),
                ),
                homeDates(resolved(playing, first, dates), playing, group),
            )
            assertEquals(album, null, albumLineYear(album, "1974"))
        }
    }

    @Test
    fun aTrackFirstOutLaterInTheOriginalsYearKeepsItsOwnDate() {
        // A bonus track first out on *X (Expanded)*, 20 November 2020, in the group of *X*, first
        // released on 10 January 2020; played on the 2022 deluxe edition.
        val bonus = sundown.copy(title = "Bonus", artists = "Someone", album = "X (Deluxe)", year = 2022)
        val dates =
            listOf(
                ReleaseDate("X", "2020-01-10", "g-x", "Album"),
                ReleaseDate("X (Expanded)", "2020-11-20", "g-x", "Album"),
                ReleaseDate("X (Deluxe)", "2022", "g-x", "Album"),
            )
        val group = mapOf("g-x" to MbGroupFirst("g-x", "X", "2020-01-10"))
        val expanded = FirstRelease("2020-11-20", "X (Expanded)", "g-x", "r-x-expanded", "Album")
        assertEquals(
            listOf(
                DateSentence.Edition("2020-01-10", "X", EditionPart.Same("2022")),
                DateSentence.First("2020-11-20", "X (Expanded)", null, album = true),
            ),
            homeDates(resolved(bonus, expanded, dates), bonus, group),
        )
        // Out on the original's date, or one less precise in its year: the original album.
        for (date in listOf("2020-01-10", "2020-01", "2020")) {
            val original = FirstRelease(date, "X", "g-x", "r-x", "Album")
            assertEquals(
                date,
                DateSentence.FirstOriginal("X"),
                homeDates(resolved(bonus, original, dates), bonus, group)[1],
            )
        }
    }

    @Test
    fun theAlbumLineDropsItsYearOnlyWhenTheNameGivesALaterOrTheSameEditionYear() {
        assertEquals(null, albumLineYear("Material Girl (2024 Remaster)", "1985"))
        assertEquals(null, albumLineYear("Actually: Further Listening 1987 - 1988 (2018 Remaster)", "2001"))
        assertEquals(null, albumLineYear("Rumours (2004 Edition)", "2004"))
        assertEquals(null, albumLineYear("Abbey Road - 2019 Remaster", "1969"))
        // No edition year in the name: the year stays.
        assertEquals("2008", albumLineYear("Thriller 25 Super Deluxe Edition", "2008"))
        assertEquals("2004", albumLineYear("Rumours (Remastered)", "2004"))
        // A year before the release's is the name's own.
        assertEquals("1973", albumLineYear("Paris 1919 Remastered", "1973"))
        assertEquals(null, albumLineYear("Material Girl (2024 Remaster)", null))
    }
}
