package app.sleevesight.app.pipeline

import app.sleevesight.app.ui.home.HomeFixtures
import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.AiOutcome
import app.sleevesight.core.pipeline.EventLine
import app.sleevesight.core.pipeline.InsightItem
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.AlbumFound
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.synthesis.GroundingCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An item's insight that found nothing, in the diagnostics (#1163): the line says from how many lines
 * it was to be written, and which sources of its lines could not be reached, so a "the sources give
 * nothing" names its cause; a kept one served again says it is that nothing was found. The album's
 * (#1187): its own lookups not reached named, and gathered again on *Retry*, with no call.
 */
class AppItemNothingNotesTest {
    private val provider = CountingProvider().apply { reply = { GroundingCheck.NO_EVIDENCE } }

    // What the album's lookup answers, and how many times it was asked; while [waiting] is set, it
    // waits for it: under way.
    private var album = AlbumFound(emptyList())
    private var albumLookups = 0
    private var waiting: CompletableDeferred<Unit>? = null
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = CardCache(),
                albums = {
                    albumLookups++
                    waiting?.await()
                    album
                },
            ),
        )
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC))
    private val events = mutableListOf<PipelineEvent>()

    private fun TestScope.notes() =
        AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder), events = { events += it })

    private val teardrop = NowPlaying("Teardrop", "Massive Attack, Elizabeth Fraser", "Mezzanine", player = "p")

    // *Teardrop*: a line about the song; with MusicBrainz [reached], one about the band (unless not
    // [band]), else none, MusicBrainz having ended unreachable, or, with [notFound], answered with no
    // recording; Last.fm as [lastFm] ended, if asked.
    private fun track(
        reached: Boolean,
        band: Boolean = reached,
        lastFm: SourceState? = null,
        notFound: Boolean = false,
    ): TrackState {
        val song = HomeFixtures.draft(SourceId.WIKIPEDIA, "Teardrop is a song by Massive Attack.")
        val band =
            HomeFixtures
                .draft(SourceId.WIKIPEDIA, "Massive Attack are an English band.", scope = Scope.ARTIST)
                .copy(about = "Massive Attack")
                .takeIf { band }
        val drafts = listOfNotNull(song, band)
        val musicBrainz =
            HomeFixtures.done(
                when {
                    reached -> Outcome.SongArticle
                    notFound -> Outcome.None
                    else -> Outcome.Unreachable
                },
            )
        return HomeFixtures
            .track(
                teardrop,
                sources =
                    mapOf(
                        SourceId.MUSICBRAINZ to musicBrainz,
                        SourceId.WIKIPEDIA to HomeFixtures.done(evidence = drafts),
                    ) + listOfNotNull(lastFm?.let { SourceId.LASTFM to it }),
                evidence = EvidenceSet.of(drafts, JvmHasher),
            ).copy(
                resolution =
                    if (reached) {
                        Resolution(
                            outcome = Outcome.SongArticle,
                            recordingMbid = "rec-teardrop",
                            artists = listOf(ResolvedArtist("Massive Attack", "mb-massive-attack")),
                        )
                    } else if (notFound) {
                        Resolution.NONE
                    } else {
                        Resolution.unreachable("rate limited")
                    },
            )
    }

    private fun lines(item: String = "artist") = events.map(EventLine::of).filter { "$item insight" in it }

    @Test
    fun `MusicBrainz not reached, the artist's nothing to write from names it in the diagnostics`() =
        runTest {
            val notes = notes()
            val track = track(reached = false)
            notes.write(track, InsightItem.ARTIST)
            advanceUntilIdle()
            val shown = notes.item(track, InsightItem.ARTIST).first { it is ItemShown.Shown } as ItemShown.Shown
            assertEquals(InsightState.NoEvidence, shown.state)
            assertEquals(listOf(SourceId.MUSICBRAINZ), shown.evidence.unanswered)
            assertEquals(0, provider.requests)
            val ended = events.filterIsInstance<PipelineEvent.AiEnded>().single()
            assertEquals(AiOutcome.NOTHING_FOUND, ended.outcome)
            assertTrue(lines().last(), "nothing found from 0 lines, MB unreachable, 0 calls" in lines().last())
        }

    @Test
    fun `a source not reached, then asked again and answering with nothing, gathers the artist's evidence again`() =
        runTest {
            val notes = notes()
            val unreached = track(reached = true, band = false, lastFm = HomeFixtures.done(Outcome.Unreachable))
            notes.write(unreached, InsightItem.ARTIST)
            advanceUntilIdle()
            val before = notes.item(unreached, InsightItem.ARTIST).first { it is ItemShown.Shown } as ItemShown.Shown
            assertEquals(listOf(SourceId.LASTFM), before.evidence.unanswered)

            // *Retry*: Last.fm answers, knowing nothing of the band. The same lines, the same artist: but
            // no longer a source not reached, so the evidence is gathered again, naming none.
            val answered = track(reached = true, band = false, lastFm = HomeFixtures.done(Outcome.None))
            val after = notes.item(answered, InsightItem.ARTIST).first { it !is ItemShown.Gathering } as ItemShown.Shown
            assertTrue(after.evidence.unanswered.isEmpty())
            assertTrue(!after.evidence.unreachable)
            assertEquals(0, provider.requests)
        }

    @Test
    fun `a model that found nothing says from how many lines, and its kept answer says nothing was found`() =
        runTest {
            val track = track(reached = true)
            notes().write(track, InsightItem.ARTIST)
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertTrue(lines().last(), "nothing found from 1 line, 1 call" in lines().last())

            // The next play's row finds it kept, with no call.
            events.clear()
            val again = notes()
            again.item(track, InsightItem.ARTIST).first { (it as? ItemShown.Shown)?.state == InsightState.NoEvidence }
            advanceUntilIdle()
            assertEquals(1, provider.requests)
            assertTrue(lines().single(), "kept answer served (nothing found), no call" in lines().single())
        }

    @Test
    fun `the album's own lookup not reached is named, and a Retry gathers it again, with no call`() =
        runTest {
            album = AlbumFound(emptyList(), unanswered = listOf(SourceId.WIKIPEDIA))
            val notes = notes()
            val track = track(reached = true)
            notes.write(track, InsightItem.ALBUM)
            advanceUntilIdle()
            val before = notes.item(track, InsightItem.ALBUM).first { it is ItemShown.Shown } as ItemShown.Shown
            assertEquals(InsightState.NoEvidence, before.state)
            assertEquals(listOf(SourceId.WIKIPEDIA), before.evidence.unanswered)
            assertTrue(
                lines("album").last(),
                "nothing found from 0 lines, W unreachable, 0 calls" in lines("album").last(),
            )
            val asked = albumLookups

            // *Retry*: Wikipedia answers with a line about the album; gathered again at once, though
            // kept for a while as one a source could not be asked for. Offered, not written.
            album =
                AlbumFound(
                    listOf(HomeFixtures.draft(SourceId.WIKIPEDIA, "Mezzanine is an album.", scope = Scope.RELEASE)),
                )
            notes.gatherAgain(track, InsightItem.ALBUM)
            advanceUntilIdle()
            assertEquals(asked + 1, albumLookups)
            assertEquals(ItemShown.Offered, notes.item(track, InsightItem.ALBUM).first { it !is ItemShown.Gathering })
            assertEquals(0, provider.requests)
        }

    @Test
    fun `the album with every source answered and no line names none`() =
        runTest {
            val notes = notes()
            val track = track(reached = true)
            notes.write(track, InsightItem.ALBUM)
            advanceUntilIdle()
            val shown = notes.item(track, InsightItem.ALBUM).first { it is ItemShown.Shown } as ItemShown.Shown
            assertEquals(InsightState.NoEvidence, shown.state)
            assertTrue(shown.evidence.unanswered.isEmpty())
            assertTrue(lines("album").last(), "nothing found from 0 lines, 0 calls" in lines("album").last())
        }

    @Test
    fun `a Retry asking MusicBrainz too gathers the album once, after the track's run, from what it found`() =
        runTest {
            album = AlbumFound(emptyList(), unanswered = listOf(SourceId.WIKIPEDIA))
            val notes = notes()
            val before = track(reached = false)
            notes.write(before, InsightItem.ALBUM)
            advanceUntilIdle()
            val held = notes.item(before, InsightItem.ALBUM).first { it is ItemShown.Shown } as ItemShown.Shown
            assertEquals(listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA), held.evidence.unanswered)
            val asked = albumLookups

            // The track playing, as the pipeline shows it, followed whether a panel is open or not.
            val tracks = MutableStateFlow<TrackState?>(before)
            val following = launch { notes.gatherAfterRuns(tracks) }
            advanceUntilIdle()

            // *Retry*: MusicBrainz asked again by the track's run, the album not from the track it
            // replaces, nor while the run asks.
            notes.gatherAgain(before, InsightItem.ALBUM, afterRun = true)
            advanceUntilIdle()
            tracks.value = before.copy(sources = before.sources + (SourceId.MUSICBRAINZ to SourceState.Pending))
            advanceUntilIdle()
            assertEquals(asked, albumLookups)

            // The run lands, MusicBrainz finding no recording (still no release group), the panel
            // closed meanwhile: the album gathered once, from it, with nothing following it.
            waiting = CompletableDeferred()
            album =
                AlbumFound(
                    listOf(HomeFixtures.draft(SourceId.WIKIPEDIA, "Mezzanine is an album.", scope = Scope.RELEASE)),
                )
            val after = track(reached = false, notFound = true)
            tracks.value = after
            advanceUntilIdle()
            assertEquals(asked + 1, albumLookups)
            // Opened again while it gathers, as a panel and a page: they join it.
            val row = launch { notes.item(after, InsightItem.ALBUM).collect {} }
            val page = launch { notes.item(after, InsightItem.ALBUM).collect {} }
            advanceUntilIdle()
            assertEquals(asked + 1, albumLookups)
            checkNotNull(waiting).complete(Unit)
            advanceUntilIdle()
            assertEquals(ItemShown.Offered, notes.item(after, InsightItem.ALBUM).first { it !is ItemShown.Gathering })
            assertEquals(asked + 1, albumLookups)
            assertEquals(0, provider.requests)
            row.cancel()
            page.cancel()
            following.cancel()
        }

    @Test
    fun `two taps on Retry during one lookup of the album ask it once, and its outcome is taken`() =
        runTest {
            album = AlbumFound(emptyList(), unanswered = listOf(SourceId.WIKIPEDIA))
            val notes = notes()
            val track = track(reached = true)
            notes.write(track, InsightItem.ALBUM)
            advanceUntilIdle()
            val asked = albumLookups
            waiting = CompletableDeferred()
            album =
                AlbumFound(
                    listOf(HomeFixtures.draft(SourceId.WIKIPEDIA, "Mezzanine is an album.", scope = Scope.RELEASE)),
                )
            notes.gatherAgain(track, InsightItem.ALBUM)
            advanceUntilIdle()
            notes.gatherAgain(track, InsightItem.ALBUM)
            advanceUntilIdle()
            assertEquals(asked + 1, albumLookups)
            checkNotNull(waiting).complete(Unit)
            advanceUntilIdle()
            // Followed again (a panel opened, the page): what it gathered is taken, not asked for again.
            assertEquals(ItemShown.Offered, notes.item(track, InsightItem.ALBUM).first { it !is ItemShown.Gathering })
            assertEquals(asked + 1, albumLookups)
        }

    @Test
    fun `a Retry whose track's run finds the same sources not reached still gathers the album afresh`() =
        runTest {
            album = AlbumFound(emptyList(), unanswered = listOf(SourceId.WIKIPEDIA))
            val notes = notes()
            val before = track(reached = false)
            notes.write(before, InsightItem.ALBUM)
            advanceUntilIdle()
            val asked = albumLookups
            notes.gatherAgain(before, InsightItem.ALBUM, afterRun = true)
            // The same state again: what was gathered before the *Retry* is not taken as it is.
            album =
                AlbumFound(
                    listOf(HomeFixtures.draft(SourceId.WIKIPEDIA, "Mezzanine is an album.", scope = Scope.RELEASE)),
                )
            val again = track(reached = false)
            assertEquals(ItemShown.Offered, notes.item(again, InsightItem.ALBUM).first { it is ItemShown.Offered })
            assertEquals(asked + 1, albumLookups)
        }
}
