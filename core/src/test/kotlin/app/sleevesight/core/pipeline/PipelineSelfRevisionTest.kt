package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.SelfRevision
import app.sleevesight.core.synthesis.SelfRevisionTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A reply that revises itself in its text (#798, `SelfRevision`) is not shown as written: when what
 * follows the revision is usable, that is shown, with no second call, and the reply kept as written
 * is cut again when it is served; else it is asked for once more, a second paid call within the same
 * granted call, and the card counts the tokens of every reply of the call; a second reply with
 * nothing usable leaves the error state; a Stop cancels the second call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineSelfRevisionTest {
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    private fun TestScope.pipeline(
        provider: AiProvider,
        cache: PipelineCache = PipelineCache.None,
        adapter: FakeAdapter = FakeAdapter(SourceId.MUSICBRAINZ),
    ): Pipeline =
        Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = listOf(adapter),
                resolver = FakeResolver(),
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("sk-test")),
                cache = cache,
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { settings }

    private fun TestScope.recording(pipeline: Pipeline): List<PipelineEvent> {
        val events = mutableListOf<PipelineEvent>()
        backgroundScope.launch { pipeline.events.toList(events) }
        runCurrent()
        return events
    }

    private fun granted(): Granted =
        Granted().also { control -> RunStep.entries.forEach { control.grants.getValue(it).complete(Unit) } }

    // A provider that replies with [replies] in turn, the last one from then on.
    private fun replying(vararg replies: String): FakeProvider {
        var n = 0
        return FakeProvider { FakeProvider.done(replies[minOf(n++, replies.lastIndex)]) }
    }

    private fun List<PipelineEvent>.revisions() = filterIsInstance<PipelineEvent.SelfRevised>()

    private fun revised(
        state: TrackState,
        sign: SelfRevision,
        salvaged: Boolean = false,
        cached: Boolean = false,
    ) = PipelineEvent.SelfRevised(state.normalized.lookupKey, sign, salvaged, cached)

    @Test
    fun `a reply that revises itself is cut to what follows, with no second call`() =
        runTest {
            val cache = MemoryCache()
            val provider = replying(REVISED)
            val pipeline = pipeline(provider, cache)
            val events = recording(pipeline)
            val state = pipeline.run(sundown, granted())
            runCurrent()

            assertEquals(1, provider.requests.size)
            val written = assertIs<InsightState.Written>(state.insight)
            assertEquals(CLEAN, written.grounded.text)
            assertTrue(written.grounded.withdrawn!!.startsWith("It is a song from nothing"))
            // Kept as written, and cut again when served.
            assertEquals(listOf(REVISED), cache.cards.values.toList())
            assertEquals(listOf(revised(state, SelfRevision.ASIDE, salvaged = true)), events.revisions())
            assertTrue(events.filterIsInstance<PipelineEvent.Grounded>().single().selfCorrected)
        }

    @Test
    fun `a correction set as a header in emphasis is cut to the corrected sentence`() =
        runTest {
            val provider = replying(HEADED)
            val state = pipeline(provider).run(sundown, granted())

            assertEquals(1, provider.requests.size)
            assertEquals("It came out in 1975 [MB1].", assertIs<InsightState.Written>(state.insight).grounded.text)
        }

    @Test
    fun `formatting, an introduction or an odd space hides no correction, fresh or kept`() =
        runTest {
            val corrected = "It came out in 1975 [MB1]."
            for (reply in SelfRevisionTest.HIDDEN) {
                val cache = MemoryCache()
                val provider = replying(reply)
                val fresh = pipeline(provider, cache).run(sundown, granted())
                assertEquals(1, provider.requests.size, reply)
                assertEquals(corrected, assertIs<InsightState.Written>(fresh.insight).grounded.text, reply)
                assertEquals(listOf(reply), cache.cards.values.toList(), reply)

                // Kept as written, served cut, with no call.
                val served = replying(CLEAN)
                val kept = pipeline(served, cache).run(sundown, granted())
                assertEquals(0, served.requests.size, reply)
                assertEquals(corrected, assertIs<InsightState.Written>(kept.insight).grounded.text, reply)
            }
        }

    @Test
    fun `a refusal is declined, whatever its text, with no revision logged`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done(ENDS, StopReason.REFUSAL) }
            val pipeline = pipeline(provider)
            val events = recording(pipeline)
            val control = granted()
            control.retries.send(false)
            val state = pipeline.run(sundown, control)
            runCurrent()

            assertEquals(InsightState.Failed(ReplyFailures.DECLINED), state.insight)
            assertEquals(1, provider.requests.size)
            assertEquals(emptyList(), events.revisions())
        }

    @Test
    fun `a reply with nothing usable after its revision is asked again, the second shown, kept and counted`() =
        runTest {
            val cache = MemoryCache()
            val provider = replying(ENDS, CLEAN)
            val pipeline = pipeline(provider, cache)
            val events = recording(pipeline)
            val control = granted()
            val state = pipeline.run(sundown, control)
            runCurrent()

            assertEquals(2, provider.requests.size)
            val written = assertIs<InsightState.Written>(state.insight)
            assertEquals(CLEAN, written.grounded.text)
            // Both replies' tokens: the card cost two calls.
            val usage = FakeProvider.USAGE
            assertEquals(Usage(usage.inputTokens * 2, usage.outputTokens * 2), written.usage)
            assertEquals(listOf(CLEAN), cache.cards.values.toList())
            assertEquals(listOf(CLEAN), control.cardsWritten)
            // Two calls in the log, one granted call ended, nothing offered again.
            assertEquals(2, events.count { it is PipelineEvent.ModelCall })
            assertEquals(2, events.count { it is PipelineEvent.ModelDone })
            assertEquals(listOf(revised(state, SelfRevision.ASIDE)), events.revisions())
            assertEquals(1, events.count { it is PipelineEvent.Grounded })
            assertEquals(1, control.ended.count { it == RunStep.MODEL_CALL })
            assertEquals(0, control.retriesAsked)
        }

    @Test
    fun `the card counts every reply of the call, a NO_EVIDENCE between them too`() =
        runTest {
            // MusicBrainz's first-release line names the album, so the player's release line is left
            // out of the prompt (#424), and `NO_EVIDENCE` to it asks again with it.
            val adapter =
                FakeAdapter(SourceId.MUSICBRAINZ) { _, _ ->
                    val first = "“Carefree Highway” was first released in 1974, on the album “Sundown”."
                    SourceResult(Outcome.SongArticle, listOf(line(SourceId.MUSICBRAINZ, first)), Duration.ZERO)
                }
            val provider = replying(ENDS, GroundingCheck.NO_EVIDENCE, CLEAN)
            val highway = nowPlaying("Carefree Highway", "Gordon Lightfoot", "Sundown")
            val state = pipeline(provider, adapter = adapter).run(highway, granted())

            assertEquals(3, provider.requests.size)
            assertTrue("(player metadata, release)" in provider.requests[2].prompt)
            val usage = FakeProvider.USAGE
            assertEquals(
                Usage(usage.inputTokens * 3, usage.outputTokens * 3),
                assertIs<InsightState.Written>(state.insight).usage,
            )
        }

    @Test
    fun `when the second reply has nothing usable either, the error state shows and nothing is kept`() =
        runTest {
            val cache = MemoryCache()
            val provider = replying(ENDS, ENDS_TOO)
            val pipeline = pipeline(provider, cache)
            val events = recording(pipeline)
            val control = granted()
            control.retries.send(false)
            val state = pipeline.run(sundown, control)
            runCurrent()

            assertEquals(2, provider.requests.size)
            assertEquals(InsightState.Failed(ReplyFailures.SELF_REVISED), state.insight)
            assertEquals(emptyMap(), cache.cards)
            assertEquals(emptyList(), control.cardsWritten)
            assertEquals(
                listOf(revised(state, SelfRevision.ASIDE), revised(state, SelfRevision.ASIDE)),
                events.filterIsInstance<PipelineEvent.SelfRevised>(),
            )
            assertEquals(0, events.count { it is PipelineEvent.Grounded })
            // Write is offered again, as after any failed call: a tap asks afresh.
            assertEquals(1, control.retriesAsked)
        }

    @Test
    fun `a clean reply, one that opens with a quoted Wait, is shown at once`() =
        runTest {
            val provider = replying(QUOTED_WAIT)
            val state = pipeline(provider).run(sundown, granted())

            assertEquals(1, provider.requests.size)
            assertEquals(QUOTED_WAIT, assertIs<InsightState.Written>(state.insight).grounded.text)
        }

    @Test
    fun `a Stop while the second call streams cancels it and keeps nothing`() =
        runTest {
            val cache = MemoryCache()
            var n = 0
            val provider = FakeProvider(delay = 10.seconds) { FakeProvider.done(if (n++ == 0) ENDS else CLEAN) }
            val pipeline = pipeline(provider, cache)
            val events = recording(pipeline)
            val control = granted()
            control.retries.send(false)
            backgroundScope.launch { pipeline.run(sundown, control) }
            // The first reply ends at 10 s, and the second call starts at once.
            advanceTimeBy(12.seconds)
            assertEquals(2, provider.requests.size)
            assertIs<InsightState.Writing>(pipeline.state.value!!.insight)

            control.stop.complete(Unit)
            runCurrent()
            assertEquals(InsightState.Stopped, pipeline.state.value!!.insight)
            assertEquals(1, events.count { it is PipelineEvent.ModelStopped })
            assertEquals(1, events.count { it is PipelineEvent.ModelDone })
            assertEquals(emptyMap(), cache.cards)
            assertEquals(emptyList(), control.cardsWritten)
            assertEquals(1, control.ended.count { it == RunStep.MODEL_CALL })
        }

    @Test
    fun `a kept card that revises itself is served cut, or not at all when nothing usable is left`() =
        runTest {
            val cache = MemoryCache()
            pipeline(replying(CLEAN), cache).run(sundown, granted())
            assertEquals(1, cache.cards.size)

            // Kept before the check, or by an older version: cut, with no call.
            cache.cards.replaceAll { _, _ -> HEADED }
            val served = replying(CLEAN)
            val first = pipeline(served, cache)
            val firstEvents = recording(first)
            val shown = first.run(sundown, granted())
            runCurrent()
            assertEquals(0, served.requests.size)
            assertEquals("It came out in 1975 [MB1].", assertIs<InsightState.Written>(shown.insight).grounded.text)
            assertEquals(
                listOf(revised(shown, SelfRevision.ASIDE, salvaged = true, cached = true)),
                firstEvents.filterIsInstance<PipelineEvent.SelfRevised>(),
            )

            // Nothing usable: not served, and the track is written afresh.
            cache.cards.replaceAll { _, _ -> ENDS }
            val provider = replying(CLEAN)
            val pipeline = pipeline(provider, cache)
            val events = recording(pipeline)
            val state = pipeline.run(sundown, granted())
            runCurrent()
            assertEquals(1, provider.requests.size)
            assertEquals(CLEAN, assertIs<InsightState.Written>(state.insight).grounded.text)
            assertTrue(events.none { it is PipelineEvent.InsightCached })
            assertEquals(listOf(revised(state, SelfRevision.ASIDE, cached = true)), events.revisions())
            assertEquals(listOf(CLEAN), cache.cards.values.toList())
        }

    private companion object {
        // A draft, a comment that cites a line (so `SelfCorrection` leaves it), and the insight again.
        const val REVISED =
            "It is a song from nothing in particular [MB1].\n\n" +
                "Wait, I need to correct that: the line says it is a song [MB1], so I'll keep only that.\n\n" +
                "It is a song [MB1]."

        // A draft, then a correction that ends the reply: nothing usable after it.
        const val ENDS =
            "It is a song from nothing in particular [MB1].\n\n" +
                "Wait, I need to correct that, so I'll keep only what the line says [MB1]."
        const val ENDS_TOO = "It is a song [MB1]. Hmm, that is all the evidence lines give."
        const val HEADED = "It came out in 1974 [MB1].\n\n**Correction:** It came out in 1975 [MB1]."
        const val CLEAN = "It is a song [MB1]."
        const val QUOTED_WAIT = "\"Wait, Let Me Fix It\" is not this song, which came out in 1974 [MB1]."
    }
}
