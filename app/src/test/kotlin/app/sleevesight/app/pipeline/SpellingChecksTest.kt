package app.sleevesight.app.pipeline

import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.pipeline.FoundAs
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.pipeline.TrackState
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.resolution.MbArtistSearch
import app.sleevesight.core.resolution.MbRecording
import app.sleevesight.core.resolution.MbRecordingDetail
import app.sleevesight.core.resolution.MbWork
import app.sleevesight.core.resolution.MusicBrainzLookup
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.resolution.RecordingResolver
import app.sleevesight.core.resolution.RememberedMusicBrainz
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.ResolvedArtist
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.SearchAnswer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The spelling check's question to MusicBrainz (#1293), its resolver held until each test lets it
 * answer: *Clear history* during it applies nothing after the clear; *Stop* during it cancels it and
 * drops the spelling; an answer past *Keep history for* is no longer held.
 */
class SpellingChecksTest {
    private val normalizer = Normalizer(JvmAccentFolder)
    private val playing =
        NowPlaying(title = "Sundwn", artists = "Gordon Lightfoot", duration = 180.seconds, player = Settings.SPOTIFY)
    private val track =
        TrackState(
            playing,
            normalizer.normalize(playing),
            sources =
                mapOf(
                    SourceId.MUSICBRAINZ to
                        SourceState.Done(SourceResult(Outcome.None, emptyList(), Duration.ZERO), false),
                ),
        )
    private val spelling = SearchAnswer.Spelling("Sundown", emptyList(), "https://en.wikipedia.org/wiki/Sundown")
    private val accepted =
        Resolution(
            Outcome.SongArticle,
            recordingMbid = "rec",
            artists = listOf(ResolvedArtist("Gordon Lightfoot", "gl")),
        )

    // The resolver, held until [answer] completes.
    private val answer = CompletableDeferred<Resolution>()
    private val resolver = TrackResolver { _, _ -> answer.await() }
    private val parts =
        MutableStateFlow<PipelineParts?>(PipelineParts(normalizer, emptyList(), resolver, JvmHasher as Hasher))
    private val settings = MutableStateFlow<Settings?>(Settings())
    private val clock = MovedClock(Instant.parse("2026-11-17T12:00:00Z"))

    private val found = mutableListOf<FoundAs>()
    private val dropped = mutableListOf<NowPlaying>()
    private var forgotten = 0

    // The host's spellings, as `PipelineHost` holds them: added when found, all gone when forgotten.
    private val held = mutableMapOf<NowPlaying, FoundAs>()

    private fun TestScope.checks(
        epoch: ClearEpoch = ClearEpoch(),
        parts: MutableStateFlow<PipelineParts?> = this@SpellingChecksTest.parts,
    ): SpellingChecks {
        val answers = AiSearchAnswers(GenreVocabulary.load(JvmResourceReader), JvmAccentFolder)
        val searches = AppAiSearches(parts, settings, answers, backgroundScope)
        val targets =
            SpellingTargets(
                { shown, f ->
                    found += f
                    held[shown] = f
                },
                { dropped += it },
                {
                    forgotten++
                    held.clear()
                },
            )
        val names = NameMatcher(JvmAccentFolder)
        return SpellingChecks(searches, parts, names, backgroundScope, targets, settings, clock, epoch)
    }

    /** MusicBrainz held until [gate] opens: each search counted, nothing found but an empty list. */
    private class GatedMusicBrainz(
        private val gate: CompletableDeferred<Unit>,
    ) : MusicBrainzLookup {
        val searched = mutableListOf<Pair<String, String>>()

        override suspend fun searchRecordings(
            title: String,
            artist: String,
        ): Lookup<List<MbRecording>> {
            searched += title to artist
            gate.await()
            return Lookup.Found(emptyList())
        }

        override suspend fun searchRecordingsBefore(
            title: String,
            artist: String,
            date: String,
        ): Lookup<List<MbRecording>> = Lookup.NotFound

        override suspend fun recording(mbid: String): Lookup<MbRecordingDetail> = Lookup.NotFound

        override suspend fun recordingReleases(mbid: String): Lookup<MbRecording> = Lookup.NotFound

        override suspend fun work(mbid: String): Lookup<MbWork> = Lookup.NotFound

        override suspend fun searchArtists(name: String): Lookup<MbArtistSearch> = Lookup.NotFound
    }

    @Test
    fun `MusicBrainz's answer to a question a clear overtook is not remembered after the clear`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val gated = GatedMusicBrainz(gate)
            val epoch = ClearEpoch()
            val remembered = RememberedMusicBrainz(gated, epoch = epoch)
            val resolver = RecordingResolver(remembered, NameMatcher(JvmAccentFolder))
            val real =
                MutableStateFlow<PipelineParts?>(
                    PipelineParts(normalizer, emptyList(), TrackResolver(resolver::resolve), JvmHasher as Hasher),
                )
            val checks = checks(epoch, real)
            checks.ask(track, spelling, "m", clock.at)
            runCurrent()
            val (title, artist) = gated.searched.first()
            // *Clear history* as the app runs it: the epoch advanced, MusicBrainz's memory cleared, the
            // question answering meanwhile, and only then the spelling check's own memory.
            epoch.advance()
            remembered.forget()
            gate.complete(Unit)
            runCurrent()
            checks.forget()
            val asked = gated.searched.size
            remembered.searchRecordings(title, artist)
            assertEquals("asked again, not remembered", asked + 1, gated.searched.size)
            assertTrue(found.isEmpty())
        }

    @Test
    fun `Stop before the question is registered keeps it from being asked`() =
        runTest {
            val checks = checks()
            checks.stop(track, spelling)
            checks.ask(track, spelling, "m", clock.at)
            answer.complete(accepted)
            runCurrent()
            assertTrue(found.isEmpty())
            assertEquals(SpellingOutcome.Stopped, checks.outcome(track, spelling).first())
        }

    @Test
    fun `what is written by itself waits while MusicBrainz is asked, and not once it found nothing`() =
        runTest {
            val checks = checks()
            checks.ask(track, spelling, "m", clock.at)
            runCurrent()
            assertEquals(true, checks.pendingFor(track).first())
            answer.complete(Resolution(Outcome.None))
            runCurrent()
            assertEquals(SpellingOutcome.NotFound, checks.outcome(track, spelling).first())
            assertEquals(false, checks.pendingFor(track).first())
        }

    @Test
    fun `a clear while MusicBrainz is asked applies nothing after it`() =
        runTest {
            val checks = checks()
            checks.ask(track, spelling, "m", clock.at)
            runCurrent()
            checks.forget()
            answer.complete(accepted)
            runCurrent()
            assertTrue(found.isEmpty())
            assertTrue(checks.outcomes.value.isEmpty())
            assertEquals(1, forgotten)
        }

    @Test
    fun `a spelling applied as a clear starts is forgotten by the clear, wherever the host held it`() =
        runTest {
            val checks = checks()
            checks.ask(track, spelling, "m", clock.at)
            answer.complete(accepted)
            runCurrent()
            assertEquals(1, held.size)
            checks.forget()
            assertTrue(held.isEmpty())
            assertTrue(checks.outcomes.value.isEmpty())
        }

    @Test
    fun `MusicBrainz confirming the spelling hands it on`() =
        runTest {
            val checks = checks()
            checks.ask(track, spelling, "m", clock.at)
            answer.complete(accepted)
            runCurrent()
            assertEquals("Sundown", found.single().title)
            assertEquals(SpellingOutcome.Found, checks.outcome(track, spelling).first())
        }

    @Test
    fun `Stop while MusicBrainz is asked cancels it and drops the spelling`() =
        runTest {
            val checks = checks()
            checks.ask(track, spelling, "m", clock.at)
            runCurrent()
            checks.stop(track, spelling)
            answer.complete(accepted)
            runCurrent()
            assertTrue(found.isEmpty())
            assertEquals(listOf(playing), dropped)
            assertEquals(SpellingOutcome.Stopped, checks.outcome(track, spelling).first())
        }

    @Test
    fun `an answer past Keep history for is no longer held, and asked again`() =
        runTest {
            val checks = checks()
            checks.ask(track, spelling, "m", clock.at)
            answer.complete(accepted)
            runCurrent()
            assertEquals(SpellingOutcome.Found, checks.outcome(track, spelling).first())
            settings.value = Settings(historyLifetime = 7.days)
            clock.at += 8.days
            assertNull(checks.outcome(track, spelling).first())
            checks.ask(track, spelling, "m", clock.at)
            runCurrent()
            assertEquals(2, found.size)
        }
}
