package app.sleevesight.app.ui.home

import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.YearTag
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.resolution.MbGroupFirst
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.OriginalRecording
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.ReleaseType
import app.sleevesight.core.synthesis.Released
import app.sleevesight.core.synthesis.SearchAnswer
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The sentences of the *Dates* panel under the album (#724): which dates are said, in which order,
 * and how; how they read is `DatesScreenTest`'s.
 */
class HomeDatesTest {
    private val grace = FirstRelease("1994-08-23", "Grace", "g-grace", "r-grace", "Album")

    private val soRealName = "So Real: Songs From Jeff Buckley"

    // #1099's track: *It's a Sin* on the 2001 edition of *Actually*, first out on the single.
    private val sin =
        sundown.copy(
            title = "It’s a Sin",
            artists = "Pet Shop Boys",
            album = "Actually: Further Listening 1987 - 1988 (2018 Remaster)",
            year = 2001,
        )

    private val sinSingle = FirstRelease("1987-06-15", "It’s a Sin", "g-sin", "r-sin", "Single")

    private val sinDates =
        listOf(
            ReleaseDate("Actually", "1987-09-07", "g-actually", "Album"),
            ReleaseDate("Actually / Further Listening 1987–1988", "2001-06-04", "g-actually", "Album"),
            ReleaseDate("It’s a Sin", "1987-06-15", "g-sin", "Single"),
        )

    // *Further Listening*'s 2018 remaster, as *Dates* says it after *Actually* (#1116).
    private val furtherListening2018 =
        EditionPart.Remaster(
            YearTag("2018", true, "Actually: Further Listening 1987 - 1988"),
            "Actually: Further Listening 1987 - 1988",
            "2001-06-04",
        )

    // *Actually*'s release group as MusicBrainz's lookup of it gives it.
    private val actually = mapOf("g-actually" to MbGroupFirst("g-actually", "Actually", "1987-09-07"))

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
                DateSentence.First("1994-08-23", "Grace", null, album = true),
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
            listOf(DateSentence.Album("1974-01"), DateSentence.FirstHere()),
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
            listOf(DateSentence.Album("1974-01"), DateSentence.FirstHere()),
            homeDates(resolved(sundown, byGroup, listOf(sundownAlbum)), sundown),
        )
        // The screenshot's album (#724): the player's "Vol. 2" is MusicBrainz's "Volume 2".
        val volume = "This Warm December: A Brushfire Holiday, Volume 2"
        val december = sundown.copy(album = "This Warm December, A Brushfire Holiday Vol. 2")
        val first = FirstRelease("2011-11-15", volume, "g-twd", "r-twd", "Album")
        val twd = resolved(december, first, listOf(ReleaseDate(volume, "2011-11-15", "g-twd")))
        assertEquals(listOf(DateSentence.Album("2011-11-15"), DateSentence.FirstHere()), homeDates(twd, december))
        assertEquals(HeaderYears(album = "2011"), headerYears(twd, december))
    }

    @Test
    fun aReissueInTheOriginalsReleaseGroupIsNotWhereTheTrackFirstCameOut() {
        // #1065: MusicBrainz files *Thriller 25 Super Deluxe Edition* (2008) in *Thriller*'s release
        // group, where *Beat It* first came out in 1982.
        val deluxe = "Thriller 25 Super Deluxe Edition"
        val beatIt = sundown.copy(title = "Beat It", artists = "Michael Jackson", album = deluxe, year = 2008)
        val thriller = FirstRelease("1982-11-30", "Thriller", "g-thriller", "r-thriller", "Album")
        val dates =
            listOf(ReleaseDate("Thriller", "1982-11-30", "g-thriller"), ReleaseDate(deluxe, "2008-02-08", "g-thriller"))
        val edition = DateSentence.Album("2008-02-08")
        assertEquals(
            listOf(edition, DateSentence.First("1982-11-30", "Thriller", null, album = true)),
            homeDates(resolved(beatIt, thriller, dates), beatIt),
        )
        // With *Thriller*'s group looked up, the deluxe set is an edition of it, said so (#1099): the
        // original first, then the edition by its name, and the track on the original (#1116).
        val group = mapOf("g-thriller" to MbGroupFirst("g-thriller", "Thriller", "1982-11-30"))
        assertEquals(
            listOf(
                DateSentence.Edition("1982-11-30", "Thriller", EditionPart.Named(deluxe, "2008-02-08")),
                DateSentence.FirstOriginal("Thriller"),
            ),
            homeDates(resolved(beatIt, thriller, dates), beatIt, group),
        )
        assertEquals(HeaderYears(album = "2008"), headerYears(resolved(beatIt, thriller, dates), beatIt))
        // With no year from the player, the year shown is MusicBrainz's: the same.
        val unyeared = beatIt.copy(year = null)
        assertEquals(HeaderYears(album = "2008"), headerYears(resolved(unyeared, thriller, dates), unyeared))
        assertEquals(
            listOf(edition, DateSentence.First("1982-11-30", "Thriller", null, album = true)),
            homeDates(resolved(unyeared, thriller, dates), unyeared),
        )
        // A player's year MusicBrainz does not give the album is overridden by the one it does.
        val misdated = beatIt.copy(year = 1982)
        assertEquals(HeaderYears(album = "2008"), headerYears(resolved(misdated, thriller, dates), misdated))
        assertEquals(
            listOf(edition, DateSentence.First("1982-11-30", "Thriller", null, album = true)),
            homeDates(resolved(misdated, thriller, dates), misdated),
        )
        // The group's release of the album's own year is still this album, on another day too.
        val sameYear = thriller.copy(date = "2008-01-15")
        val withSameYear = listOf(ReleaseDate("Thriller", "2008-01-15", "g-thriller"), dates[1])
        assertEquals(HeaderYears(album = "2008"), headerYears(resolved(beatIt, sameYear, withSameYear), beatIt))
        assertEquals(
            listOf(edition, DateSentence.FirstHere()),
            homeDates(resolved(beatIt, sameYear, withSameYear), beatIt),
        )
        // A first release in the group with no readable year is still this album, as before.
        assertEquals(
            listOf(edition, DateSentence.FirstHere()),
            homeDates(resolved(beatIt, thriller.copy(date = ""), dates), beatIt),
        )
    }

    @Test
    fun anEditionUnderAnotherTitleSaysItsDateAndTheAlbumsFirstRelease() {
        // #1099: *It's a Sin* on Spotify's *Actually: Further Listening 1987 - 1988 (2018 Remaster)*,
        // 2001; MusicBrainz files *Further Listening* (2001, 2018) in *Actually*'s release group, first
        // released on 7 September 1987. The track came out first on the single, in June 1987.
        // The name's 2018 remaster is later than the 2001 edition MusicBrainz dates (#1116).
        val state = resolved(sin, sinSingle, sinDates)
        assertEquals(
            listOf(
                DateSentence.Edition("1987-09-07", "Actually", furtherListening2018),
                DateSentence.First("1987-06-15", "It’s a Sin", ReleaseKind.SINGLE),
            ),
            homeDates(state, sin, actually),
        )
        // The header's year is the edition's, as before; the album line shows none, the name giving one.
        assertEquals(HeaderYears(album = "2001"), headerYears(state, sin))
        assertEquals(null, albumLineYear(sin.album.orEmpty(), "2001"))
        // The same from MusicBrainz's result as the retrieval cache kept it.
        val kept = kept("1987-06-15", sinDates).copy(firstRelease = sinSingle)
        assertEquals(homeDates(state, sin, actually), homeDates(cached(sin, kept), sin, actually))
        // The group asked for is the album's.
        assertEquals("g-actually", editionGroup(state, sin))
        // A single's edition names the single.
        val singles = sinDates.map { it.copy(type = "Single") }
        assertEquals(
            DateSentence.Edition("1987-09-07", "Actually", furtherListening2018, ReleaseKind.SINGLE),
            homeDates(resolved(sin, sinSingle, singles), sin, actually).first(),
        )
    }

    @Test
    fun anEditionWhoseGroupIsNotLookedUpSaysWhatItSaidBefore() {
        // Not yet asked, failed, or offline: no group known, and the album's sentence as before, even
        // with an earlier release of the group among the track's: the 2001 release as the original,
        // and its 2018 remaster that the player's name gives (#1116).
        val tag = YearTag("2018", true, "Actually: Further Listening 1987 - 1988")
        val before = DateSentence.Edition("2001-06-04", sinDates[1].title, EditionPart.Its(tag))
        assertEquals(before, homeDates(resolved(sin, sinSingle, sinDates), sin).first())
        // Another group's answer is not this album's.
        val other = mapOf("g-other" to MbGroupFirst("g-other", "Actually", "1987-09-07"))
        assertEquals(before, homeDates(resolved(sin, sinSingle, sinDates), sin, other).first())
        // A group MusicBrainz gives no readable first date of says nothing either.
        val undated = mapOf("g-actually" to MbGroupFirst("g-actually", "Actually", null))
        assertEquals(before, homeDates(resolved(sin, sinSingle, sinDates), sin, undated).first())
        // Several groups of the name in the year shown: none is asked, as which album it is is not known.
        val two = sinDates.take(2).map { it.copy(group = "g-x") } + sinDates[1].copy(group = "g-y", date = "2001-07")
        assertEquals(null, editionGroup(resolved(sin, sinSingle, two), sin))
    }

    @Test
    fun anEditionWhoseGroupsFirstReleaseHoldsNotTheRecordingIsToldByTheGroup() {
        // A bonus recording, first out on the album's 1997 reissue *X*, then on *X (Deluxe)* in 2009;
        // the album itself came out in 1959, with no recording of it. The track's releases say
        // nothing of 1959: the group's lookup does.
        val bonus = sundown.copy(title = "Outtake", artists = "Someone", album = "X (Deluxe)", year = 2009)
        val first = FirstRelease("1997", "X", "g-x", "r-x", "Album")
        val dates = listOf(ReleaseDate("X", "1997", "g-x", "Album"), ReleaseDate("X (Deluxe)", "2009", "g-x", "Album"))
        val group = mapOf("g-x" to MbGroupFirst("g-x", "X", "1959-03"))
        val expected =
            listOf(
                DateSentence.Edition("1959-03", "X", EditionPart.Same("2009")),
                DateSentence.First("1997", "X", null, album = true),
            )
        assertEquals(expected, homeDates(resolved(bonus, first, dates), bonus, group))
        val kept = kept("1997", dates).copy(firstRelease = first)
        assertEquals(expected, homeDates(cached(bonus, kept), bonus, group))
        // Not looked up: what the track's releases say, never the 1997 reissue as the album's first.
        assertEquals(
            listOf(DateSentence.Album("2009"), DateSentence.First("1997", "X", null, album = true)),
            homeDates(cached(bonus, kept), bonus),
        )
    }

    @Test
    fun afterAnEditionTheTrackFirstOutOnItSaysItsOwnDateNotThisAlbum() {
        // The bonus recording first out on the 1997 reissue *X*, played on it; the album came out in
        // March 1959. "This album" after the edition's sentence would read as the 1959 one.
        val reissue = sundown.copy(title = "Outtake", artists = "Someone", album = "X", year = 1997)
        val first = FirstRelease("1997", "X", "g-x", "r-x", "Album")
        val dates = listOf(ReleaseDate("X", "1997", "g-x", "Album"), ReleaseDate("X (Deluxe)", "2009", "g-x", "Album"))
        val group = mapOf("g-x" to MbGroupFirst("g-x", "X", "1959-03"))
        val expected =
            listOf(
                DateSentence.Edition("1959-03", "X", EditionPart.Same("1997")),
                DateSentence.First("1997", "X", null, album = true),
            )
        assertEquals(expected, homeDates(resolved(reissue, first, dates), reissue, group))
        val kept = kept("1997", dates).copy(firstRelease = first)
        assertEquals(expected, homeDates(cached(reissue, kept), reissue, group))
        // Not looked up: "this album", as before.
        assertEquals(
            listOf(DateSentence.Album("1997"), DateSentence.FirstHere()),
            homeDates(cached(reissue, kept), reissue),
        )
    }

    @Test
    fun anEditionUnderTheAlbumsOwnTitleSaysTheAlbumThenThisEdition() {
        // A remaster in the group, titled as the album but for its edition, *Rumours (Remastered)*.
        val dreams =
            sundown.copy(title = "Dreams", artists = "Fleetwood Mac", album = "Rumours (Remastered)", year = 2004)
        val album = FirstRelease("1977-02-04", "Rumours", "g-rumours", "r-rumours", "Album")
        val dates =
            listOf(
                ReleaseDate("Rumours", "1977-02-04", "g-rumours", "Album"),
                ReleaseDate("Rumours (Remastered)", "2004-03-22", "g-rumours", "Album"),
            )
        val group = mapOf("g-rumours" to MbGroupFirst("g-rumours", "Rumours", "1977-02-04"))
        assertEquals(
            listOf(
                DateSentence.Edition("1977-02-04", "Rumours", EditionPart.Same("2004-03-22")),
                DateSentence.FirstOriginal("Rumours"),
            ),
            homeDates(resolved(dreams, album, dates), dreams, group),
        )
        // An EP's edition names the EP.
        val eps = dates.map { it.copy(type = "EP") }
        assertEquals(
            DateSentence.Edition("1977-02-04", "Rumours", EditionPart.Same("2004-03-22"), ReleaseKind.EP),
            homeDates(resolved(dreams, album, eps), dreams, group).first(),
        )
    }

    @Test
    fun theGroupsFirstReleaseIsOriginallyReleasedAlone() {
        // *Actually* played in its first release: "originally", no edition.
        val first = sin.copy(album = "Actually", year = 1987)
        val onSingle = DateSentence.First("1987-06-15", "It’s a Sin", ReleaseKind.SINGLE)
        assertEquals(
            listOf(DateSentence.Album("1987-09-07"), onSingle),
            homeDates(resolved(first, sinSingle, sinDates), first, actually),
        )
        // A group's first date less precise than the release's, in its year, is not earlier.
        val inYear = mapOf("g-actually" to MbGroupFirst("g-actually", "Actually", "1987"))
        assertEquals(
            DateSentence.Album("1987-09-07"),
            homeDates(resolved(first, sinSingle, sinDates), first, inYear).first(),
        )
        // Released on the group's first date under its title, editions aside: the first release.
        val deluxe = first.copy(album = "Actually (Deluxe)")
        val deluxeDates = listOf(ReleaseDate("Actually (Deluxe)", "1987-09-07", "g-actually", "Album"))
        assertEquals(
            DateSentence.Album("1987-09-07"),
            homeDates(resolved(deluxe, sinSingle, deluxeDates), deluxe, actually).first(),
        )
        // Under another title on the group's first date: an edition, the group named.
        val other = first.copy(album = "Pet Shop Boys, Actually")
        val otherDates = listOf(ReleaseDate("Pet Shop Boys, Actually", "1987-09-07", "g-actually", "Album"))
        assertEquals(
            DateSentence.Edition("1987-09-07", "Actually", EditionPart.Named(other.album.orEmpty(), "1987-09-07")),
            homeDates(resolved(other, sinSingle, otherDates), other, actually).first(),
        )
        // A pressing of the same title out days earlier in the year: the original, not an edition.
        val daysBefore = mapOf("g-actually" to MbGroupFirst("g-actually", "Actually", "1987-09-01"))
        assertEquals(
            DateSentence.Album("1987-09-07"),
            homeDates(resolved(first, sinSingle, sinDates), first, daysBefore).first(),
        )
        // Under another title in the same year, it is one: the group named.
        assertEquals(
            DateSentence.Edition("1987-09-01", "Actually", EditionPart.Named(other.album.orEmpty(), "1987-09-07")),
            homeDates(resolved(other, sinSingle, otherDates), other, daysBefore).first(),
        )
        // A group's first date after the release's (MusicBrainz should not give one) says no edition.
        val later = mapOf("g-actually" to MbGroupFirst("g-actually", "Actually", "1988"))
        assertEquals(
            DateSentence.Album("1987-09-07"),
            homeDates(resolved(first, sinSingle, sinDates), first, later).first(),
        )
    }

    @Test
    fun theFirstReleaseSettlesTheYearOfAnAlbumWhoseNameHasReleasesInSeveralYears() {
        // #1058: *Thriller* on Spotify, no year; MusicBrainz names the 1982 original and reissues.
        val thrillerTrack =
            sundown.copy(
                title = "Thriller",
                artists = "Michael Jackson",
                album = "Thriller",
                year = null,
            )
        val first = FirstRelease("1982-11-30", "Thriller", "g-thriller", "r-thriller", "Album")
        val dates =
            listOf(
                ReleaseDate("Thriller", "1982-11-30", "g-thriller", "Album"),
                ReleaseDate("Thriller", "2001-10-16", "g-special", "Album"),
                ReleaseDate("Thriller", "2022-11-18", "g-forty", "Album"),
            )
        val state = resolved(thrillerTrack, first, dates)
        assertEquals(HeaderYears(album = "1982"), headerYears(state, thrillerTrack))
        assertEquals(
            listOf(DateSentence.Album("1982-11-30"), DateSentence.FirstHere()),
            homeDates(state, thrillerTrack),
        )
        // A player's year that is none of them decides nothing either: the first release does.
        val misdated = thrillerTrack.copy(year = 1999)
        assertEquals(HeaderYears(album = "1982"), headerYears(resolved(misdated, first, dates), misdated))
        // A player's year that is one of them still decides, as before: the 2001 edition.
        val special = thrillerTrack.copy(year = 2001)
        assertEquals(
            listOf(DateSentence.Album("2001-10-16"), DateSentence.First("1982-11-30", "Thriller", null, album = true)),
            homeDates(resolved(special, first, dates), special),
        )
        // The first release on another title: no year shown, and its date said with its title.
        val elsewhere = FirstRelease("1982-10-18", "The Girl Is Mine", "g-girl", "r-girl", "Single")
        val other = resolved(thrillerTrack, elsewhere, dates)
        assertEquals(HeaderYears(), headerYears(other, thrillerTrack))
        assertEquals(
            listOf(DateSentence.First("1982-10-18", "The Girl Is Mine", ReleaseKind.SINGLE)),
            homeDates(other, thrillerTrack),
        )
        // A release of the name, in none of their years: none either, and its date said with its
        // title, never a bare "this album".
        val offYear = first.copy(date = "1983-02")
        assertEquals(HeaderYears(), headerYears(resolved(thrillerTrack, offYear, dates), thrillerTrack))
        assertEquals(
            listOf(DateSentence.First("1983-02", "Thriller", null, album = true)),
            homeDates(resolved(thrillerTrack, offYear, dates), thrillerTrack),
        )
    }

    @Test
    fun aTitleTracksSingleBesideAnAlbumOfTheNameSettlesNoYear() {
        // The single of the title in 1989, the album in 1990 and reissued in 2000: the album is
        // played, so the single's year is not its own.
        val x = sundown.copy(title = "X", artists = "Someone", album = "X", year = null)
        val single = FirstRelease("1989-05", "X", "g-single", "r-single", "Single")
        val dates =
            listOf(
                ReleaseDate("X", "1989-05", "g-single", "Single"),
                ReleaseDate("X", "1990-02", "g-album", "Album"),
                ReleaseDate("X", "2000-03", "g-reissue", "Album"),
            )
        assertEquals(HeaderYears(), headerYears(resolved(x, single, dates), x))
        assertEquals(
            listOf(DateSentence.First("1989-05", "X", ReleaseKind.SINGLE)),
            homeDates(resolved(x, single, dates), x),
        )
        // With only singles of the name, in two years, the first one settles it, and is named so.
        val singles = listOf(dates[0], ReleaseDate("X", "2010", "g-single-2", "Single"))
        assertEquals(
            listOf(DateSentence.Album("1989-05", ReleaseKind.SINGLE), DateSentence.FirstHere(ReleaseKind.SINGLE)),
            homeDates(resolved(x, single, singles), x),
        )
    }

    @Test
    fun withNoAlbumSentenceTheFirstReleaseIsNeverABareThisAlbum() {
        // The album of the name named, in the year the album line shows, but undated: no album
        // sentence, so the first release says its own date.
        val first = FirstRelease("1974-01", "Sundown", null, "r-1", "Album")
        val undated = resolved(sundown.copy(year = 1974), first, listOf(ReleaseDate("Sundown", null, "g-sundown")))
        assertEquals(
            listOf(DateSentence.First("1974-01", "Sundown", null, album = true)),
            homeDates(undated, sundown.copy(year = 1974)),
        )
    }

    @Test
    fun aSingleOrAnEpBeingPlayedIsNamedAsSuch() {
        // #1058: *Material Girl* is MusicBrainz's 1985 single.
        val girl = sundown.copy(title = "Material Girl", artists = "Madonna", album = "Material Girl")
        val dates = listOf(ReleaseDate("Material Girl", "1985-01-23", "g-girl", "Single"))
        val single = FirstRelease("1985-01-23", "Material Girl", "g-girl", "r-girl", "Single")
        assertEquals(
            listOf(DateSentence.Album("1985-01-23", ReleaseKind.SINGLE), DateSentence.FirstHere(ReleaseKind.SINGLE)),
            homeDates(resolved(girl, single, dates), girl),
        )
        // First released elsewhere: the single's own sentence names it, the first release its own.
        val album = FirstRelease("1984-11-12", "Like a Virgin", "g-virgin", "r-virgin", "Album")
        assertEquals(
            listOf(
                DateSentence.Album("1985-01-23", ReleaseKind.SINGLE),
                DateSentence.First("1984-11-12", "Like a Virgin", null, album = true),
            ),
            homeDates(resolved(girl, album, dates), girl),
        )
        // A single and an EP of the name in one year, no album: the earlier one, whose date it says.
        val both =
            listOf(
                ReleaseDate("Material Girl", "1985-03", "g-ep", "EP"),
                ReleaseDate("Material Girl", "1985-01-23", "g-girl", "Single"),
            )
        assertEquals(
            DateSentence.Album("1985-01-23", ReleaseKind.SINGLE),
            homeDates(resolved(girl, album, both), girl).first(),
        )
        val epFirst = listOf(both[0].copy(date = "1985-01-02"), both[1])
        assertEquals(
            DateSentence.Album("1985-01-02", ReleaseKind.EP),
            homeDates(resolved(girl, album, epFirst), girl).first(),
        )
        // One of no type known among them: an album, as before.
        assertEquals(
            DateSentence.Album("1985-01-23"),
            homeDates(resolved(girl, album, listOf(both[0], both[1].copy(type = null))), girl).first(),
        )
        // An EP likewise.
        val four = sundown.copy(album = "Four")
        val ep = FirstRelease("1983-05", "Four", "g-four", "r-four", "EP")
        assertEquals(
            listOf(DateSentence.Album("1983-05", ReleaseKind.EP), DateSentence.FirstHere(ReleaseKind.EP)),
            homeDates(resolved(four, ep, listOf(ReleaseDate("Four", "1983-05", "g-four", "EP"))), four),
        )
    }

    @Test
    fun anAiAnswersFirstReleaseOfTheAlbumsTitleInAnEarlierYearIsNotThisAlbum() {
        // A 2008 edition of *Thriller*: the answer dates the album 2008, its first release 1982.
        val edition = sundown.copy(title = "Beat It", artists = "Michael Jackson", album = "Thriller")
        val first = Released("1982-11-30", "Thriller", ReleaseType.ALBUM)
        assertEquals(
            listOf(DateSentence.Album("2008-02-08"), DateSentence.First("1982-11-30", "Thriller", null, album = true)),
            aiDates(SearchAnswer.Dates(album = "2008-02-08", first = first), edition),
        )
        // In the album's year, it is this album.
        assertEquals(
            listOf(DateSentence.Album("1982-11-30"), DateSentence.FirstHere()),
            aiDates(SearchAnswer.Dates(album = "1982-11-30", first = first), edition),
        )
    }

    @Test
    fun aFreshOriginalWithItsReleaseWinsOverAKeptOneWithout() {
        // A look again that found the release a failed lookup missed keeps the earlier state.
        val keptOriginal = cohen.copy(release = null)
        val result =
            SourceResult(
                Outcome.SongArticle,
                emptyList(),
                1.seconds,
                releaseDates = listOf(soReal),
                original = keptOriginal,
            )
        val state =
            track(hallelujah, sources = mapOf(SourceId.MUSICBRAINZ to SourceState.Done(result, cached = true)))
                .copy(resolution = Resolution(releaseDates = listOf(soReal), original = cohen))
        assertEquals(
            DateSentence.Original("1984-06", "Leonard Cohen", "Various Positions", null),
            homeDates(state, hallelujah).last(),
        )
        // With no fresh one, the kept original says what it can.
        assertEquals(
            DateSentence.Original("1984-06", "Leonard Cohen", null, null),
            homeDates(cached(hallelujah, result), hallelujah).last(),
        )
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
            listOf(DateSentence.Album("1989-03-21"), DateSentence.FirstHere()),
            homeDates(resolved(prayer, album, dates), prayer),
        )
        assertEquals(HeaderYears(album = "1989"), headerYears(resolved(prayer, single, dates), prayer))
        // An EP of the title is left out the same way, and an album of no known type is the album.
        val ep = listOf(dates[0].copy(type = "EP"), dates[1].copy(type = null))
        assertEquals(DateSentence.Album("1989-03-21"), homeDates(resolved(prayer, null, ep), prayer).single())
        // With only a single of the title named, it is the single played, and named so (#1058).
        assertEquals(
            listOf(DateSentence.Album("1989-03-03", ReleaseKind.SINGLE), DateSentence.FirstHere(ReleaseKind.SINGLE)),
            homeDates(resolved(prayer, single, dates.take(1)), prayer),
        )
        // The album named but undated, the single dated in the year: no album date, and the single
        // is not the album.
        val undatedAlbum = listOf(dates[0], dates[1].copy(date = null))
        assertEquals(listOf(onSingle), homeDates(resolved(prayer, single, undatedAlbum), prayer))
        // Kept before the types: as before, the earliest of the year, the single's group among the album's.
        val untyped = dates.map { it.copy(type = null) }
        assertEquals(
            listOf(DateSentence.Album("1989-03-03"), DateSentence.FirstHere()),
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
            listOf(DateSentence.First("1994-08-23", "Grace", null, album = true)),
            homeDates(resolved(none, grace, listOf(soReal, ReleaseDate("Grace", "1994-08-23", "g-grace"))), none),
        )
        assertEquals(HeaderYears(), headerYears(resolved(none, grace, listOf(soReal)), none))
    }

    @Test
    fun theAlbumsYearFromThePlayerAloneGivesNoDate() {
        val tagged = hallelujah.copy(album = "Rock Ballads Forever", year = 2003)
        val track = resolved(tagged, grace, listOf(soReal))
        assertEquals(HeaderYears(album = "2003"), headerYears(track, tagged))
        assertEquals(listOf(DateSentence.First("1994-08-23", "Grace", null, album = true)), homeDates(track, tagged))
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
