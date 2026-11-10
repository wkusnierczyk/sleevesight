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
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.synthesis.GroundingCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An item's insight that found nothing, in the diagnostics (#1163): the line says from how many lines
 * it was to be written, and which sources of its lines could not be reached, so a "the sources give
 * nothing" names its cause; a kept one served again says it is that nothing was found.
 */
class AppItemNothingNotesTest {
    private val provider = CountingProvider().apply { reply = { GroundingCheck.NO_EVIDENCE } }
    private val parts =
        MutableStateFlow<PipelineParts?>(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = emptyList(),
                resolver = TrackResolver { _, _ -> Resolution.NONE },
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("key")),
                cache = CardCache(),
            ),
        )
    private val settings = MutableStateFlow<Settings?>(Settings(aiProvider = ProviderId.ANTHROPIC))
    private val events = mutableListOf<PipelineEvent>()

    private fun TestScope.notes() =
        AppItemNotes(parts, settings, this, NameMatcher(JvmAccentFolder), events = { events += it })

    private val teardrop = NowPlaying("Teardrop", "Massive Attack, Elizabeth Fraser", "Mezzanine", player = "p")

    // *Teardrop*: a line about the song; with MusicBrainz [reached], one about the band (unless not
    // [band]), else none, MusicBrainz having ended unreachable; Last.fm as [lastFm] ended, if asked.
    private fun track(
        reached: Boolean,
        band: Boolean = reached,
        lastFm: SourceState? = null,
    ): TrackState {
        val song = HomeFixtures.draft(SourceId.WIKIPEDIA, "Teardrop is a song by Massive Attack.")
        val band =
            HomeFixtures
                .draft(SourceId.WIKIPEDIA, "Massive Attack are an English band.", scope = Scope.ARTIST)
                .copy(about = "Massive Attack")
                .takeIf { band }
        val drafts = listOfNotNull(song, band)
        val musicBrainz = HomeFixtures.done(if (reached) Outcome.SongArticle else Outcome.Unreachable)
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
                    } else {
                        Resolution.unreachable("rate limited")
                    },
            )
    }

    private fun lines() = events.map(EventLine::of).filter { "artist insight" in it }

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
}
