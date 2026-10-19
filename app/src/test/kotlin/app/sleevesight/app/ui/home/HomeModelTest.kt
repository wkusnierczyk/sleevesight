package app.sleevesight.app.ui.home

import app.sleevesight.app.pipeline.PipelineView
import app.sleevesight.app.ui.home.HomeFixtures.carefree
import app.sleevesight.app.ui.home.HomeFixtures.done
import app.sleevesight.app.ui.home.HomeFixtures.draft
import app.sleevesight.app.ui.home.HomeFixtures.evidence
import app.sleevesight.app.ui.home.HomeFixtures.sundown
import app.sleevesight.app.ui.home.HomeFixtures.track
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.sources.FirstRelease
import app.sleevesight.core.sources.Rejection
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** What the home screen works out from what it is given, without a screen. */
class HomeModelTest {
    @Test
    fun thePipelinesTrackIsShownOnlyWhileTheSessionShowsIt() {
        val view = PipelineView(track(sundown))
        assertSame(view.track, trackFor(view, sundown))
        // A new length or album of the same track plays on.
        assertSame(view.track, trackFor(view, sundown.copy(duration = 4.minutes, album = "Other")))
        // The next track is shown at once, without the last one's evidence or insight.
        assertNull(trackFor(view, carefree))
        assertNull(trackFor(PipelineView(), sundown))
    }

    @Test
    fun theReleaseYearIsTheFirstReleasesFromMusicBrainz() {
        fun released(date: String?) =
            track().copy(
                resolution = date?.let { Resolution(firstRelease = FirstRelease(it, "Sundown", null, "rel", "Album")) },
            )
        assertEquals("1974", releaseYear(released("1974-03-25")))
        assertEquals("1974", releaseYear(released("1974")))
        assertNull(releaseYear(released("19")))
        // No resolution (every source from the cache), or none found.
        assertNull(releaseYear(released(null)))
        assertNull(releaseYear(null))
    }

    @Test
    fun aFreshRunAndAFullyCachedRunShowTheSameYear() {
        val date = "1974-03-25"
        val result = SourceResult(Outcome.SongArticle, emptyList(), 1.seconds, firstReleased = date)
        val fresh =
            track(sources = mapOf(SourceId.MUSICBRAINZ to SourceState.Done(result, cached = false))).copy(
                resolution = Resolution(firstRelease = FirstRelease(date, "Sundown", null, "rel", "Album")),
            )
        // Every source from the cache: no resolution, MusicBrainz's result as it was kept.
        val cached = track(sources = mapOf(SourceId.MUSICBRAINZ to SourceState.Done(result, cached = true)))
        assertEquals("1974", releaseYear(fresh))
        assertEquals(releaseYear(fresh), releaseYear(cached))
    }

    @Test
    fun aSentenceSplitsIntoTextAndCitations() {
        assertEquals(
            listOf(
                Segment.Text("It was recorded in 1973 "),
                Segment.Cite("MB1"),
                Segment.Cite("W1"),
                Segment.Text("."),
            ),
            segments("It was recorded in 1973 [MB1][W1]."),
        )
        assertEquals(listOf(Segment.Text("Little else is documented.")), segments("Little else is documented."))
        // Not a citation: lowercase, or too many letters.
        assertEquals(listOf(Segment.Text("See [w1] and [ABC1].")), segments("See [w1] and [ABC1]."))
    }

    @Test
    fun aCitationPointsAtALineTheModelOrNothing() {
        assertEquals(CiteKind.LINE, citeKind("MB1", evidence, modelAllowed = false))
        assertEquals(CiteKind.UNKNOWN, citeKind("L1", evidence, modelAllowed = false))
        assertEquals(CiteKind.MODEL, citeKind("M", evidence, modelAllowed = true))
        assertEquals(CiteKind.UNKNOWN, citeKind("M", evidence, modelAllowed = false))
        assertEquals(CiteKind.UNKNOWN, citeKind("MB1", null, modelAllowed = false))
    }

    @Test
    fun theChipsFollowTheEvidenceOrderAndSayEachSourcesState() {
        val withRelease =
            EvidenceSet.of(
                listOf(HomeFixtures.musicBrainzLine, draft(SourceId.PLAYER, "On the release \"Sundown\".")),
                JvmHasher,
            )
        val state =
            track(
                sources =
                    mapOf(
                        SourceId.WEB to SourceState.Pending,
                        SourceId.WIKIPEDIA to done(Outcome.None, cached = true),
                        SourceId.MUSICBRAINZ to SourceState.Failed("IllegalStateException"),
                    ),
                evidence = withRelease,
            )
        val chips = sourceChips(state, needsKey = setOf(SourceId.LASTFM), modelAllowed = true)
        assertEquals(
            listOf(
                SourceId.PLAYER,
                SourceId.MUSICBRAINZ,
                SourceId.WIKIPEDIA,
                SourceId.WEB,
                SourceId.LASTFM,
                SourceId.MODEL,
            ),
            chips.map { it.source },
        )
        assertEquals(
            listOf(
                ChipStatus.Release,
                ChipStatus.Failed,
                ChipStatus.Done(Outcome.None, cached = true),
                ChipStatus.Pending,
                ChipStatus.NeedsKey,
                ChipStatus.ModelAllowed,
            ),
            chips.map { it.status },
        )
        // Wikipedia's chip shows and hides Wikidata's lines too.
        assertEquals(setOf(SourceId.WIKIPEDIA, SourceId.WIKIDATA), chips[2].filters)
        // Without a P line there is no release chip, and without model knowledge no M chip.
        assertEquals(
            SourceId.MUSICBRAINZ,
            sourceChips(state.copy(evidence = evidence), emptySet(), false).first().source,
        )
        assertEquals(SourceId.WEB, sourceChips(state, emptySet(), false).last().source)
    }

    @Test
    fun rejectedMatchesAndUnverifiedLinesComeFromEachSourcesResult() {
        val unverified = draft(SourceId.LASTFM, "Gordon Lightfoot has 1M listeners.")
        val state =
            track(
                sources =
                    mapOf(
                        SourceId.LASTFM to done(Outcome.ArtistArticle, unverified = listOf(unverified)),
                        SourceId.WIKIPEDIA to done(Outcome.Rejected("a different Sundown")),
                        SourceId.WEB to
                            done(
                                Outcome.Rejected("unused"),
                                rejections = listOf(Rejection("Gordon Lightfoot", "another artist of the name")),
                            ),
                    ),
            )
        assertEquals(
            listOf(
                RejectedMatch(SourceId.WIKIPEDIA, null, "a different Sundown"),
                RejectedMatch(SourceId.WEB, "Gordon Lightfoot", "another artist of the name"),
            ),
            rejectedMatches(state),
        )
        assertEquals(listOf(unverified), unverifiedLines(state))
    }

    @Test
    fun aLinksHostIsShownWithoutItsPathOrWww() {
        assertEquals("en.wikipedia.org", hostOf("https://en.wikipedia.org/wiki/Sundown"))
        assertEquals("wikidata.org", hostOf("https://www.wikidata.org/wiki/Q1?x=1"))
        assertEquals("example.com", hostOf("https://user@example.com"))
        assertNull(hostOf(null))
        assertNull(hostOf("not a url"))
    }

    @Test
    fun theSourcesSayThePhoneIsOfflineOnlyWhileItIsAndASourceIsUnreachable() {
        val unreachable =
            track(sources = mapOf(SourceId.MUSICBRAINZ to done(), SourceId.WEB to done(Outcome.Unreachable)))
        assertTrue(offlineShown(PipelineView(offline = true), unreachable))
        assertFalse(offlineShown(PipelineView(offline = false), unreachable))
        assertFalse(offlineShown(PipelineView(offline = true), track()))
        assertFalse(offlineShown(PipelineView(offline = true), null))
    }

    @Test
    fun theClockShowsMinutesAndSecondsAndHoursFromAnHour() {
        assertEquals("0:00", clock(0.seconds))
        assertEquals("3:07", clock(3.minutes + 7.seconds))
        assertEquals("1:02:03", clock(1.hours + 2.minutes + 3.seconds))
    }
}
