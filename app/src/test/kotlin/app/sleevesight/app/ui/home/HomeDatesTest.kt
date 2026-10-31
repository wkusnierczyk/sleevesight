package app.sleevesight.app.ui.home

import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.OriginalRecording
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The sentences of the *Dates* panel under the album (#724): which dates are said, in which order,
 * and how; how they read is `DatesScreenTest`'s.
 */
class HomeDatesTest {
    private val grace = FirstRelease("1994-08-23", "Grace", "g-grace", "r-grace", "Album")

    private val soRealName = "So Real: Songs From Jeff Buckley"

    private val soReal = ReleaseDate(soRealName, "2007-05-29", "g-so-real")

    private val hallelujah =
        sundown.copy(title = "Hallelujah", artists = "Jeff Buckley", album = soRealName, mediaId = "spotify:track:h")

    private val cohen =
        OriginalRecording(
            recordingMbid = "mb-cohen",
            title = "Hallelujah",
            artists = emptyList(),
            artistCredit = "Leonard Cohen",
            date = "1984-06",
            release = FirstRelease("1984-12", "Various Positions", "g-vp", "r-vp", "Album"),
        )

    // [playing]'s state with a run's resolution: its [first] release, its releases' [dates], and a
    // cover's [original].
    private fun resolved(
        playing: NowPlaying,
        first: FirstRelease?,
        dates: List<ReleaseDate>,
        original: OriginalRecording? = null,
    ): TrackState =
        track(playing).copy(
            resolution = Resolution(firstRelease = first, releaseDates = dates, original = original),
        )

    // [playing]'s state with only MusicBrainz's result as the retrieval cache kept it: no resolution.
    private fun cached(
        playing: NowPlaying,
        result: SourceResult,
    ): TrackState = track(playing, sources = mapOf(SourceId.MUSICBRAINZ to SourceState.Done(result, cached = true)))

    private fun kept(
        firstReleased: String?,
        dates: List<ReleaseDate>,
    ) = SourceResult(Outcome.SongArticle, emptyList(), 1.seconds, firstReleased = firstReleased, releaseDates = dates)

    @Test
    fun aLaterAlbumGivesItsDateTheEarlierReleaseWithItsTitleAndTheCoversOriginal() {
        assertEquals(
            listOf(
                DateSentence.Album("2007-05-29"),
                DateSentence.First("1994-08-23", "Grace", null),
                DateSentence.Original("1984-06", "Leonard Cohen", "Various Positions", null),
            ),
            homeDates(resolved(hallelujah, grace, listOf(soReal), cohen), hallelujah),
        )
    }

    @Test
    fun theFirstReleaseOnTheAlbumBeingPlayedIsSaidSo() {
        val sundownAlbum = ReleaseDate("Sundown", "1974-01", "g-sundown")
        // By its title, as the album's key reads it, when it has no release group.
        val byTitle = FirstRelease("1974-01", "Sundown", null, "r-1", "Album")
        assertEquals(
            listOf(DateSentence.Album("1974-01"), DateSentence.FirstHere),
            homeDates(resolved(sundown, byTitle, listOf(sundownAlbum)), sundown),
        )
        // Not by its title when its release group is another: a single of the album's title.
        val single = FirstRelease("1973-11", "Sundown", "g-single", "r-2", "Single")
        assertEquals(
            listOf(DateSentence.Album("1974-01"), DateSentence.First("1973-11", "Sundown", ReleaseKind.SINGLE)),
            homeDates(resolved(sundown, single, listOf(sundownAlbum)), sundown),
        )
        // By its release group, the one whose year the album line shows, under another title.
        val byGroup = FirstRelease("1974-01", "Sundown (Original)", "g-sundown", "r-1", "Album")
        assertEquals(
            listOf(DateSentence.Album("1974-01"), DateSentence.FirstHere),
            homeDates(resolved(sundown, byGroup, listOf(sundownAlbum)), sundown),
        )
        // The screenshot's album (#724): the player's "Vol. 2" is MusicBrainz's "Volume 2".
        val volume = "This Warm December: A Brushfire Holiday, Volume 2"
        val december = sundown.copy(album = "This Warm December, A Brushfire Holiday Vol. 2")
        val first = FirstRelease("2011-11-15", volume, "g-twd", "r-twd", "Album")
        val twd = resolved(december, first, listOf(ReleaseDate(volume, "2011-11-15", "g-twd")))
        assertEquals(listOf(DateSentence.Album("2011-11-15"), DateSentence.FirstHere), homeDates(twd, december))
        assertEquals(HeaderYears(album = "2011"), headerYears(twd, december))
    }

    @Test
    fun aTitleTracksSingleOfTheAlbumsYearIsNotTheAlbum() {
        // "Like a Prayer": the single on 3 March 1989, the album on 21 March, one title.
        val prayer = sundown.copy(title = "Like a Prayer", artists = "Madonna", album = "Like a Prayer")
        val dates =
            listOf(
                ReleaseDate("Like a Prayer", "1989-03-03", "g-single", "Single"),
                ReleaseDate("Like a Prayer", "1989-03-21", "g-album", "Album"),
            )
        val single = FirstRelease("1989-03-03", "Like a Prayer", "g-single", "r-single", "Single")
        val onSingle = DateSentence.First("1989-03-03", "Like a Prayer", ReleaseKind.SINGLE)
        assertEquals(
            listOf(DateSentence.Album("1989-03-21"), onSingle),
            homeDates(resolved(prayer, single, dates), prayer),
        )
        val album = FirstRelease("1989-03-21", "Like a Prayer", "g-album", "r-album", "Album")
        assertEquals(
            listOf(DateSentence.Album("1989-03-21"), DateSentence.FirstHere),
            homeDates(resolved(prayer, album, dates), prayer),
        )
        assertEquals(HeaderYears(album = "1989"), headerYears(resolved(prayer, single, dates), prayer))
        // An EP of the title is left out the same way, and an album of no known type is the album.
        val ep = listOf(dates[0].copy(type = "EP"), dates[1].copy(type = null))
        assertEquals(DateSentence.Album("1989-03-21"), homeDates(resolved(prayer, null, ep), prayer).single())
        // With only a single of the title named, it is the album played.
        assertEquals(
            listOf(DateSentence.Album("1989-03-03"), DateSentence.FirstHere),
            homeDates(resolved(prayer, single, dates.take(1)), prayer),
        )
        // Kept before the types: as before, the earliest of the year, the single's group among the album's.
        val untyped = dates.map { it.copy(type = null) }
        assertEquals(
            listOf(DateSentence.Album("1989-03-03"), DateSentence.FirstHere),
            homeDates(resolved(prayer, single, untyped), prayer),
        )
        assertEquals(
            listOf(DateSentence.Album("1989-03-03"), DateSentence.First("1989-03-03", null, null)),
            homeDates(cached(prayer, kept("1989-03-03", untyped)), prayer),
        )
    }

    @Test
    fun aSingleOrAnEpIsNamedAsSuch() {
        val single = FirstRelease("1973", "Hooked on a Feeling", "g-s", "r-s", "Single")
        val ep = single.copy(type = "EP")
        val compilation = single.copy(type = "Compilation")
        val original = cohen.copy(release = cohen.release?.copy(type = "Single"))
        assertEquals(
            listOf(
                DateSentence.Album("2007-05-29"),
                DateSentence.First("1973", "Hooked on a Feeling", ReleaseKind.SINGLE),
                DateSentence.Original("1984-06", "Leonard Cohen", "Various Positions", ReleaseKind.SINGLE),
            ),
            homeDates(resolved(hallelujah, single, listOf(soReal), original), hallelujah),
        )
        assertEquals(
            DateSentence.First("1973", "Hooked on a Feeling", ReleaseKind.EP),
            homeDates(resolved(hallelujah, ep, listOf(soReal)), hallelujah)[1],
        )
        assertEquals(
            DateSentence.First("1973", "Hooked on a Feeling", null),
            homeDates(resolved(hallelujah, compilation, listOf(soReal)), hallelujah)[1],
        )
    }

    @Test
    fun aCoverWhoseOriginalsReleaseIsNotKnownSaysTheRecordingAlone() {
        assertEquals(
            DateSentence.Original("1984-06", "Leonard Cohen", null, null),
            homeDates(resolved(hallelujah, grace, listOf(soReal), cohen.copy(release = null)), hallelujah).last(),
        )
    }

    @Test
    fun aResultKeptBeforeTheTitlesSaysTheFirstReleasesDateAloneAndNoCover() {
        // Kept before #724: the first release's date and the releases' dates, no title, no original.
        val old = cached(hallelujah, kept("1994-08-23", listOf(soReal)))
        assertEquals(
            listOf(DateSentence.Album("2007-05-29"), DateSentence.First("1994-08-23", null, null)),
            homeDates(old, hallelujah),
        )
        // Kept since: the same as the run's.
        val since = cached(hallelujah, kept("1994-08-23", listOf(soReal)).copy(firstRelease = grace, original = cohen))
        assertEquals(
            homeDates(resolved(hallelujah, grace, listOf(soReal), cohen), hallelujah),
            homeDates(since, hallelujah),
        )
    }

    @Test
    fun withNoAlbumReportedTheFirstReleaseIsSaidWithItsTitle() {
        val none = hallelujah.copy(album = null)
        assertEquals(
            listOf(DateSentence.First("1994-08-23", "Grace", null)),
            homeDates(resolved(none, grace, listOf(soReal, ReleaseDate("Grace", "1994-08-23", "g-grace"))), none),
        )
        assertEquals(HeaderYears(), headerYears(resolved(none, grace, listOf(soReal)), none))
    }

    @Test
    fun theAlbumsYearFromThePlayerAloneGivesNoDate() {
        val tagged = hallelujah.copy(album = "Rock Ballads Forever", year = 2003)
        val track = resolved(tagged, grace, listOf(soReal))
        assertEquals(HeaderYears(album = "2003"), headerYears(track, tagged))
        assertEquals(listOf(DateSentence.First("1994-08-23", "Grace", null)), homeDates(track, tagged))
    }

    @Test
    fun theAlbumsDateIsTheEarliestOfItsNameInTheYearShownAsPreciseAsKnown() {
        val dates =
            listOf(
                ReleaseDate(soRealName, "2007", "g-a"),
                ReleaseDate(soRealName, "2007-09-01", "g-b"),
                ReleaseDate(soRealName, "2007-05-29", "g-c"),
            )
        assertEquals(
            DateSentence.Album("2007-05-29"),
            homeDates(resolved(hallelujah, null, dates), hallelujah).single(),
        )
        // Of two years, none shown: no date either.
        val two = listOf(ReleaseDate(soRealName, "2007", "g-a"), ReleaseDate(soRealName, "2010", "g-b"))
        assertEquals(emptyList<DateSentence>(), homeDates(resolved(hallelujah, null, two), hallelujah))
    }

    @Test
    fun withNothingKnownThereAreNoSentences() {
        assertEquals(emptyList<DateSentence>(), homeDates(resolved(hallelujah, null, emptyList()), hallelujah))
        assertEquals(emptyList<DateSentence>(), homeDates(track(hallelujah), hallelujah))
        assertEquals(emptyList<DateSentence>(), homeDates(null, hallelujah))
        // A date with no year says nothing.
        val undated = cached(hallelujah, kept("19", emptyList()))
        assertEquals(emptyList<DateSentence>(), homeDates(undated, hallelujah))
    }

    @Test
    fun aDateIsAsPreciseAsMusicBrainzGivesIt() {
        assertEquals(DateWhen.Day(29, 7, "2014"), DateWhen.of("2014-07-29"))
        assertEquals(DateWhen.Day(3, 12, "1965"), DateWhen.of("1965-12-03"))
        assertEquals(DateWhen.Month(7, "2014"), DateWhen.of("2014-07"))
        assertEquals(DateWhen.Year("2014"), DateWhen.of("2014"))
        // A part that is not a month or a day leaves it less precise; no year, no date.
        assertEquals(DateWhen.Year("2014"), DateWhen.of("2014-13-01"))
        assertEquals(DateWhen.Month(7, "2014"), DateWhen.of("2014-07-32"))
        assertNull(DateWhen.of("19"))
        assertNull(DateWhen.of(""))
    }

    @Test
    fun withNoDatesThePanelSaysWhyFromMusicBrainzsState() {
        fun of(state: SourceState?) =
            NoDates.of(track(hallelujah, sources = state?.let { mapOf(SourceId.MUSICBRAINZ to it) }.orEmpty()))
        assertEquals(NoDates.LOOKING, NoDates.of(null))
        assertEquals(NoDates.LOOKING, of(null))
        assertEquals(NoDates.LOOKING, of(SourceState.Pending))
        assertEquals(NoDates.OFF, of(HomeFixtures.done(Outcome.SourceOff)))
        assertEquals(NoDates.UNREACHABLE, of(HomeFixtures.done(Outcome.Unreachable)))
        assertEquals(NoDates.UNREACHABLE, of(SourceState.Failed("IllegalStateException")))
        assertEquals(NoDates.NOT_FOUND, of(HomeFixtures.done(Outcome.None)))
        assertEquals(NoDates.NOT_FOUND, of(HomeFixtures.done(Outcome.Rejected("by someone else"))))
        assertEquals(NoDates.NO_DATES, of(HomeFixtures.done(Outcome.SongArticle)))
        assertEquals(NoDates.NO_DATES, of(HomeFixtures.done(Outcome.SongArticle, cached = true)))
    }
}
