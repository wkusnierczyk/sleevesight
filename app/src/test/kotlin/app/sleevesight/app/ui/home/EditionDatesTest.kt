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
    fun aTrackFirstOutOnTheRemasteredSingleItselfSaysItsDateNotThisSingle() {
        // "This single" would point at the 2024 remaster being played.
        val single = FirstRelease("1985", "Material Girl", "g-girl", "r-girl", "Single")
        assertEquals(
            listOf(
                DateSentence.Edition("1985", "Material Girl", remaster2024, ReleaseKind.SINGLE),
                DateSentence.First("1985", "Material Girl", ReleaseKind.SINGLE),
            ),
            homeDates(resolved(girl, single, girlDates), girl, girlGroup),
        )
    }

    @Test
    fun aRemasteredAlbumMusicBrainzLacksNamesTheOriginalAlbumForTheTrack() {
        val virgin = girl.copy(title = "Like a Virgin", album = "Like a Virgin (2024 Remaster)")
        val dates = listOf(ReleaseDate("Like a Virgin", "1984-11-13", "g-virgin", "Album"))
        assertEquals(
            listOf(
                DateSentence.Edition(
                    "1984-11-13",
                    "Like a Virgin",
                    EditionPart.Its(YearTag("2024", true, "Like a Virgin")),
                ),
                DateSentence.FirstOriginal("Like a Virgin"),
            ),
            homeDates(resolved(virgin, likeAVirgin, dates), virgin),
        )
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
