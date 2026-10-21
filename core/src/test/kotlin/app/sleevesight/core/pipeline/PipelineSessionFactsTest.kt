package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.SentenceStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/** The artist facts an insight cites are left out of the next tracks' prompts in its session (#239). */
class PipelineSessionFactsTest {
    private val normalizer = Normalizer(JvmAccentFolder)
    private var settings = Settings(aiProvider = ProviderId.ANTHROPIC)
    private var session: Long? = 1L
    private val facts = ListeningSessionFacts { session }

    private val formed = "The band formed in 1960."
    private val label = "The band's label is Parlophone."

    private fun track(
        title: String,
        album: String? = null,
    ) = nowPlaying(title, "The Band", album)

    // MusicBrainz gives [songLines] lines about the song; Last.fm the same two about the artist on every track.
    private fun adapters(songLines: Int = 1): List<SourceAdapter> =
        listOf(
            FakeAdapter(SourceId.MUSICBRAINZ) { track, _ ->
                val lines = (1..songLines).map { line(SourceId.MUSICBRAINZ, "${track.title} is a song, fact $it.") }
                SourceResult(Outcome.SongArticle, lines, Duration.ZERO)
            },
            FakeAdapter(SourceId.LASTFM, verifies = false) { _, _ ->
                SourceResult(
                    Outcome.ArtistArticle,
                    listOf(line(SourceId.LASTFM, formed, Scope.ARTIST), line(SourceId.LASTFM, label, Scope.ARTIST)),
                    Duration.ZERO,
                )
            },
        )

    // Cites the first artist line the prompt holds, by its id; or [guess], whatever the prompt holds.
    private var guess: String? = null

    // Replies `NO_EVIDENCE` to a prompt without the artist's lines.
    private var noEvidenceWithoutArtist = false
    private val provider =
        FakeProvider { request ->
            guess?.let { return@FakeProvider FakeProvider.done("It is a song [MB1]. The band [$it].") }
            if (noEvidenceWithoutArtist && "(Last.fm, artist)" !in request.prompt) {
                return@FakeProvider FakeProvider.done(GroundingCheck.NO_EVIDENCE)
            }
            val id = listOf("L1", "L2").firstOrNull { "[$it] (Last.fm, artist)" in request.prompt }
            FakeProvider.done(if (id == null) "It is a song [MB1]." else "It is a song [MB1]. The band [$id].")
        }

    private fun TestScope.pipeline(
        adapters: List<SourceAdapter> = adapters(),
        cache: PipelineCache = PipelineCache.None,
    ): Pipeline =
        Pipeline(
            PipelineParts(
                normalizer = normalizer,
                adapters = adapters,
                resolver = FakeResolver(),
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("sk-test")),
                cache = cache,
                sessionFacts = facts,
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { settings }

    private fun TestScope.recording(pipeline: Pipeline): List<PipelineEvent> {
        val events = mutableListOf<PipelineEvent>()
        backgroundScope.launch { pipeline.events.toList(events) }
        runCurrent()
        return events
    }

    private fun prompt(n: Int) = provider.requests[n].prompt

    @Test
    fun `the next track's prompt leaves out the artist line cited, and keeps the others with their ids`() =
        runTest {
            val pipeline = pipeline()
            val events = recording(pipeline)

            pipeline.run(track("One"), write = true)
            val second = pipeline.run(track("Two"), write = true)

            assertTrue("[L1] (Last.fm, artist) $formed" in prompt(0))
            assertFalse(formed in prompt(1))
            assertTrue("[L2] (Last.fm, artist) $label" in prompt(1))
            // The insight cites the line it was given, by the whole evidence's id, and checks clean.
            val written = assertIs<InsightState.Written>(second.insight)
            assertEquals(setOf("MB1", "L2"), written.held)
            assertEquals(2, written.grounded.counts.ok)
            assertEquals(
                listOf("MB1", "L2"),
                written.grounded.paragraphs
                    .flatten()
                    .flatMap { it.cites },
            )
            // The whole evidence is the track's, shown and cited as ever.
            assertEquals(listOf("MB1", "L1", "L2"), second.evidence?.ids)
            runCurrent()
            val calls = events.filterIsInstance<PipelineEvent.ModelCall>()
            assertEquals(listOf(emptyList(), listOf("L1")), calls.map { it.leftOut })
        }

    @Test
    fun `a track with enough evidence of its own gets no artist line, and cites the whole evidence's ids`() =
        runTest {
            val pipeline = pipeline(adapters(songLines = PromptEvidence.ENOUGH_OF_ITS_OWN))
            val events = recording(pipeline)

            val state = pipeline.run(track("One"), write = true)

            assertFalse(formed in prompt(0) || label in prompt(0))
            assertTrue("[MB4] (MusicBrainz, song)" in prompt(0))
            assertEquals(listOf("MB1", "MB2", "MB3", "MB4", "L1", "L2"), state.evidence?.ids)
            runCurrent()
            assertEquals(listOf("L1", "L2"), events.filterIsInstance<PipelineEvent.ModelCall>().single().leftOut)
        }

    @Test
    fun `a citation of a line the prompt left out is unknown, not kept, and not counted as told`() =
        runTest {
            val cache = MemoryCache()
            val pipeline = pipeline(cache = cache)

            pipeline.run(track("One"), write = true)
            guess = "L1"
            val second = pipeline.run(track("Two"), write = true)

            assertFalse(formed in prompt(1))
            val sentences = assertIs<InsightState.Written>(second.insight).grounded.paragraphs.flatten()
            assertEquals(listOf(SentenceStatus.OK, SentenceStatus.UNKNOWN), sentences.map { it.status })
            // Only track one's card is kept, so a card served from the cache never cites a line it was not given.
            assertEquals(1, cache.cards.size)
            // Track two's guess is not a fact it told: for track one, L1 is still its own alone.
            val one = normalizer.normalize(track("One")).lookupKey
            assertEquals(emptySet(), facts.citedByOthers(one))
        }

    @Test
    fun `with model knowledge on, the fallback is still the artist lines, not the release alone`() =
        runTest {
            settings = settings.copy(enabledSources = settings.enabledSources + SourceSwitch.MODEL)
            val pipeline = pipeline(adapters(songLines = 0))

            pipeline.run(track("One", album = "The Album"), write = true)
            pipeline.run(track("Two", album = "The Album"), write = true)
            // Both artist lines are cited by others now: leaving them out would leave the release alone,
            // which the model may narrate from its own knowledge; the prompt has them all instead.
            pipeline.run(track("Three", album = "The Album"), write = true)

            val third = prompt(2)
            assertTrue(formed in third && label in third)
        }

    @Test
    fun `NO_EVIDENCE to a prompt that left lines out asks again at once with them all, and only that is kept`() =
        runTest {
            noEvidenceWithoutArtist = true
            val cache = MemoryCache()
            val pipeline = pipeline(adapters(songLines = PromptEvidence.ENOUGH_OF_ITS_OWN), cache = cache)
            val events = recording(pipeline)

            val state = pipeline.run(track("One"), write = true)

            assertEquals(2, provider.requests.size)
            assertFalse(formed in prompt(0))
            assertTrue(formed in prompt(1))
            val written = assertIs<InsightState.Written>(state.insight)
            assertEquals(null, written.held)
            assertEquals(
                listOf(SentenceStatus.OK, SentenceStatus.OK),
                written.grounded.paragraphs
                    .flatten()
                    .map { it.status },
            )
            assertFalse(
                cache.cards.values
                    .single()
                    .contains(GroundingCheck.NO_EVIDENCE),
            )
            runCurrent()
            val calls = events.filterIsInstance<PipelineEvent.ModelCall>()
            assertEquals(listOf(listOf("L1", "L2"), emptyList()), calls.map { it.leftOut })
        }

    @Test
    fun `a track played again in the session finds its card, though the session cited its lines since`() =
        runTest {
            val pipeline = pipeline(cache = MemoryCache())

            pipeline.run(track("One"), write = true)
            pipeline.run(track("Two"), write = true)
            val again = pipeline.run(track("One"), write = true)

            assertEquals(2, provider.requests.size)
            assertTrue(assertIs<InsightState.Written>(again.insight).cached)
        }

    @Test
    fun `a track written again is not denied the facts its own insight cited`() =
        runTest {
            val pipeline = pipeline()

            pipeline.run(track("One"), write = true)
            pipeline.run(track("One"), write = true)

            assertTrue(formed in prompt(1))
        }

    @Test
    fun `a new listening session starts with every artist fact`() =
        runTest {
            val pipeline = pipeline()

            pipeline.run(track("One"), write = true)
            session = 2L
            pipeline.run(track("Two"), write = true)

            assertTrue("[L1] (Last.fm, artist) $formed" in prompt(1))
        }

    @Test
    fun `when leaving the cited lines out would leave nothing to narrate, the prompt has them all`() =
        runTest {
            val pipeline = pipeline(adapters(songLines = 0))

            pipeline.run(track("One"), write = true)
            // Track one cited L1; track two's evidence is L1 and L2, and L2 alone is still something.
            pipeline.run(track("Two"), write = true)
            // Track three: both are cited by others now, so leaving them out would leave nothing.
            pipeline.run(track("Three"), write = true)

            assertFalse(formed in prompt(1))
            val third = prompt(2)
            assertTrue(formed in third && label in third)
        }
}
