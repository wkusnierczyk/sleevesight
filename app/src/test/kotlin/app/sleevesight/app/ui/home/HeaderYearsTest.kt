package app.sleevesight.app.ui.home

import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.ReleaseDate
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** The header's years (#556): the album's, and the recording's first release when it differs. */
class HeaderYearsTest {
    private val soundtrack = "Project Hail Mary (Original Motion Picture Soundtrack)"

    private val windOfChange = sundown.copy(title = "Wind of Change", artists = "Scorpions", album = soundtrack)

    private val dates =
        listOf(
            ReleaseDate("Crazy World", "1990-11-06"),
            ReleaseDate(soundtrack, "2026-03-20"),
        )

    // [playing]'s state with a run's resolution: first released on [first], its releases' [dates].
    private fun resolved(
        playing: NowPlaying,
        first: String?,
        dates: List<ReleaseDate> = this.dates,
    ): TrackState =
        track(playing).copy(
            resolution =
                Resolution(
                    firstRelease = first?.let { FirstRelease(it, "Crazy World", null, "rel", "Album") },
                    releaseDates = dates,
                ),
        )

    @Test
    fun aSoundtrackShowsItsOwnYearAndTheFirstReleaseApart() {
        assertEquals(
            HeaderYears(album = "2026", firstReleased = "1990"),
            headerYears(resolved(windOfChange, "1990-11-06"), windOfChange),
        )
    }

    @Test
    fun theSameYearShowsOnceAfterTheAlbum() {
        val crazyWorld = windOfChange.copy(album = "Crazy World")
        assertEquals(HeaderYears(album = "1990"), headerYears(resolved(crazyWorld, "1990-11-06"), crazyWorld))
    }

    @Test
    fun anAlbumOfUnknownYearShowsNoYearAndTheFirstReleaseApart() {
        val unknown = windOfChange.copy(album = "Rock Ballads Forever")
        assertEquals(HeaderYears(firstReleased = "1990"), headerYears(resolved(unknown, "1990-11-06"), unknown))
    }

    @Test
    fun theAlbumsYearFallsBackToThePlayersOwn() {
        val tagged = windOfChange.copy(album = "Rock Ballads Forever", year = 2003)
        assertEquals(
            HeaderYears(album = "2003", firstReleased = "1990"),
            headerYears(resolved(tagged, "1990-11-06"), tagged),
        )
        // MusicBrainz's year for the album comes first.
        val both = windOfChange.copy(year = 2025)
        assertEquals(HeaderYears(album = "2026", firstReleased = "1990"), headerYears(resolved(both, "1990"), both))
        // Without MusicBrainz, the player's year alone.
        assertEquals(HeaderYears(album = "2025"), headerYears(track(both).copy(resolution = null), both))
    }

    @Test
    fun withNoAlbumOnlyTheFirstReleaseShows() {
        val none = windOfChange.copy(album = null, year = 2026)
        assertEquals(HeaderYears(firstReleased = "1990"), headerYears(resolved(none, "1990-11-06"), none))
        val blank = windOfChange.copy(album = " ")
        assertEquals(HeaderYears(firstReleased = "1990"), headerYears(resolved(blank, "1990-11-06"), blank))
    }

    @Test
    fun anAlbumYearNoLaterThanTheFirstReleaseShowsAlone() {
        // MusicBrainz dates the album before the recording it chose: one year, the album's.
        val earlier = listOf(ReleaseDate(soundtrack, "1989"))
        assertEquals(
            HeaderYears(album = "1989"),
            headerYears(resolved(windOfChange, "1990-11-06", earlier), windOfChange),
        )
    }

    @Test
    fun withNothingKnownNoYearShows() {
        assertEquals(HeaderYears(), headerYears(resolved(windOfChange, null, emptyList()), windOfChange))
        assertEquals(HeaderYears(), headerYears(resolved(windOfChange, "19", emptyList()), windOfChange))
        assertEquals(HeaderYears(), headerYears(null, windOfChange))
    }

    @Test
    fun aFreshRunAndAFullyCachedRunShowTheSameYears() {
        val result =
            SourceResult(Outcome.SongArticle, emptyList(), 1.seconds, firstReleased = "1990-11-06")
                .copy(releaseDates = dates)
        val fresh =
            resolved(windOfChange, "1990-11-06").copy(
                sources = mapOf(SourceId.MUSICBRAINZ to SourceState.Done(result, cached = false)),
            )
        // Every source from the cache: no resolution, MusicBrainz's result as it was kept.
        val kept = mapOf(SourceId.MUSICBRAINZ to SourceState.Done(result, cached = true))
        val cached = track(windOfChange, sources = kept)
        assertEquals(HeaderYears(album = "2026", firstReleased = "1990"), headerYears(fresh, windOfChange))
        assertEquals(headerYears(fresh, windOfChange), headerYears(cached, windOfChange))
    }
}
