package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.StopReason
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * The artist facts an insight cites are left out of the next tracks' prompts in its session (#239),
 * unless *Tell an artist's facts once per session* is off (#407).
 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    // Fails the first call that holds the artist's lines.
    private var failFirstWithArtist = false

    // Streams these chunks, when it gives some for the prompt, in place of the replies above.
    private var chunks: (String) -> List<String>? = { null }
    private val provider =
        FakeProvider { request ->
            chunks(request.prompt)?.let { parts ->
                return@FakeProvider parts.map { CompletionEvent.Delta(it) } +
                    CompletionEvent.Done(FakeProvider.USAGE, StopReason.END)
            }
            guess?.let { return@FakeProvider FakeProvider.done("It is a song [MB1]. The band [$it].") }
            if (noEvidenceWithoutArtist && "(Last.fm, artist)" !in request.prompt) {
                return@FakeProvider FakeProvider.done(GroundingCheck.NO_EVIDENCE)
            }
            if (failFirstWithArtist) {
                failFirstWithArtist = false
                return@FakeProvider listOf(CompletionEvent.Error(AiErrorKind.OVERLOADED))
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

            val states = mutableListOf<InsightState?>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                pipeline.state.collect { states += it?.insight }
            }

            val state = pipeline.run(track("One"), write = true)

            assertEquals(2, provider.requests.size)
            // The first reply is asked again, not shown or reported as the track's, nor shown as it streams.
            assertFalse(InsightState.NoEvidence in states)
            val partials = states.filterIsInstance<InsightState.Writing>().map { it.partial }
            assertTrue(partials.none { GroundingCheck.NO_EVIDENCE in it }, partials.toString())
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
            assertFalse(events.any { it is PipelineEvent.NoEvidence })
        }

    // The states [pipeline] shows, each one, as `run` publishes them.
    private fun TestScope.shown(pipeline: Pipeline): List<InsightState?> {
        val states = mutableListOf<InsightState?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            pipeline.state.collect { states += it?.insight }
        }
        return states
    }

    private fun List<InsightState?>.partials() = filterIsInstance<InsightState.Writing>().map { it.partial }

    @Test
    fun `NO_EVIDENCE with punctuation after it never streams onto the card, asked again or not`() =
        runTest {
            val replies =
                listOf(listOf("NO_EVIDENCE", "."), listOf("NO_EVI", "DENCE", ": the sources ", "say nothing."))
            for ((i, reply) in replies.withIndex()) {
                session = 10L + 2 * i
                // Asked again: the fewer lines get the punctuated marker, every line a reply.
                chunks = { prompt -> reply.takeIf { "(Last.fm, artist)" !in prompt } }
                val fewer = pipeline(adapters(songLines = PromptEvidence.ENOUGH_OF_ITS_OWN))
                val states = shown(fewer)
                fewer.run(track("One"), write = true)
                assertTrue(states.partials().none { "NO_EVI" in it }, states.partials().toString())
                assertIs<InsightState.Written>(states.last())

                // Not asked again: in a new session nothing is left out, and the track ends with no evidence.
                session = 11L + 2 * i
                chunks = { reply }
                val whole = pipeline()
                val ended = shown(whole)
                whole.run(track("Two"), write = true)
                assertTrue(ended.partials().none { "NO_EVI" in it }, ended.partials().toString())
                assertEquals(InsightState.NoEvidence, ended.last())
            }
        }

    @Test
    fun `a reply that starts like the marker shows in full once it diverges, and one that never does is written`() =
        runTest {
            chunks = { listOf("N", "O", " single source names the band [MB1].") }
            val pipeline = pipeline()
            val states = shown(pipeline)
            pipeline.run(track("One"), write = true)
            assertEquals(listOf("NO single source names the band [MB1]."), states.partials().filter { it.isNotEmpty() })
            val written = assertIs<InsightState.Written>(states.last())
            assertEquals("NO single source names the band [MB1].", written.grounded.text)

            chunks = { listOf("N", "O") }
            val short = pipeline()
            val ended = shown(short)
            short.run(track("Two"), write = true)
            assertEquals(emptyList(), ended.partials().filter { it.isNotEmpty() })
            assertEquals("NO", assertIs<InsightState.Written>(ended.last()).grounded.text)
        }

    @Test
    fun `a Retry after the call with every line failed asks with every line again, not the fewer`() =
        runTest {
            noEvidenceWithoutArtist = true
            failFirstWithArtist = true
            val pipeline = pipeline(adapters(songLines = PromptEvidence.ENOUGH_OF_ITS_OWN))
            val control = Granted()
            RunStep.entries.forEach { control.grants.getValue(it).complete(Unit) }
            val run = backgroundScope.launch { pipeline.run(track("One"), control) }
            runCurrent()
            assertEquals(InsightState.Failed("overloaded"), pipeline.state.value!!.insight)

            control.retries.send(true)
            runCurrent()

            assertEquals(3, provider.requests.size)
            assertFalse(formed in prompt(0))
            assertTrue(formed in prompt(1) && formed in prompt(2))
            assertIs<InsightState.Written>(pipeline.state.value!!.insight)
            assertTrue(run.isCompleted)
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
    fun `a card written from fewer lines, served from the cache, keeps no record of the lines its prompt held`() =
        runTest {
            val pipeline = pipeline(cache = MemoryCache())
            pipeline.run(track("One"), write = true)
            val written = assertIs<InsightState.Written>(pipeline.run(track("Two"), write = true).insight)
            assertEquals(setOf("MB1", "L2"), written.held)

            val again = assertIs<InsightState.Written>(pipeline.run(track("Two"), write = true).insight)

            // A problem report on it (#390) lists every line, and says its prompt may have held fewer.
            assertTrue(again.cached)
            assertEquals(null, again.held)
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
    fun `with the facts told on every track, the next prompt has the line cited, and nothing is left out`() =
        runTest {
            settings = settings.copy(artistFactsOncePerSession = false)
            val pipeline = pipeline()
            val events = recording(pipeline)

            pipeline.run(track("One"), write = true)
            pipeline.run(track("Two"), write = true)

            assertTrue("[L1] (Last.fm, artist) $formed" in prompt(1))
            assertTrue("[L2] (Last.fm, artist) $label" in prompt(1))
            runCurrent()
            val calls = events.filterIsInstance<PipelineEvent.ModelCall>()
            assertEquals(listOf(emptyList<String>(), emptyList()), calls.map { it.leftOut })
        }

    @Test
    fun `with the facts told on every track, a track with enough evidence of its own gets the artist lines too`() =
        runTest {
            settings = settings.copy(artistFactsOncePerSession = false)
            val pipeline = pipeline(adapters(songLines = PromptEvidence.ENOUGH_OF_ITS_OWN))

            pipeline.run(track("One"), write = true)

            assertTrue(formed in prompt(0) && label in prompt(0))
        }

    @Test
    fun `facts told while they are told on every track are not told again once the switch is back on`() =
        runTest {
            settings = settings.copy(artistFactsOncePerSession = false)
            val pipeline = pipeline()

            pipeline.run(track("One"), write = true)
            settings = settings.copy(artistFactsOncePerSession = true)
            pipeline.run(track("Two"), write = true)

            assertFalse(formed in prompt(1))
            assertTrue(label in prompt(1))
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
