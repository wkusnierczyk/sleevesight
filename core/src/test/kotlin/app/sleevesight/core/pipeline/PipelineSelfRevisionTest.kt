package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.SelfRevision
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
import kotlin.time.Duration.Companion.seconds

/**
 * A reply that revises itself in its text (#798, `SelfRevision`) is neither shown nor kept: it is
 * asked for once more, a second paid call within the same granted call, logged and counted as one;
 * a second such reply leaves the error state; a Stop cancels the second call; a card kept from
 * before that revises itself is not served.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineSelfRevisionTest {
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")
    private val settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    private fun TestScope.pipeline(
        provider: AiProvider,
        cache: PipelineCache = PipelineCache.None,
    ): Pipeline =
        Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = listOf(FakeAdapter(SourceId.MUSICBRAINZ)),
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

    @Test
    fun `a reply that revises itself is asked for once more, and the second is shown, kept and counted`() =
        runTest {
            val cache = MemoryCache()
            val provider = replying(REVISED, CLEAN)
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
            assertEquals(
                listOf(PipelineEvent.SelfRevised(state.normalized.lookupKey, SelfRevision.ASIDE, askedAgain = true)),
                events.filterIsInstance<PipelineEvent.SelfRevised>(),
            )
            assertEquals(1, events.count { it is PipelineEvent.Grounded })
            assertEquals(1, control.ended.count { it == RunStep.MODEL_CALL })
            assertEquals(0, control.retriesAsked)
        }

    @Test
    fun `when the second reply revises itself too, the error state shows and nothing is kept`() =
        runTest {
            val cache = MemoryCache()
            val provider = replying(REVISED, REPEATED)
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
                listOf(
                    PipelineEvent.SelfRevised(state.normalized.lookupKey, SelfRevision.ASIDE, askedAgain = true),
                    PipelineEvent.SelfRevised(state.normalized.lookupKey, SelfRevision.REPEATED_OPENING),
                ),
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
            val provider = FakeProvider(delay = 10.seconds) { FakeProvider.done(if (n++ == 0) REVISED else CLEAN) }
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
    fun `a kept card that revises itself is not served, and the track is written afresh`() =
        runTest {
            val cache = MemoryCache()
            pipeline(replying(CLEAN), cache).run(sundown, granted())
            assertEquals(1, cache.cards.size)
            // A card kept before the check, or by an older version, that revises itself.
            cache.cards.replaceAll { _, _ -> REVISED }

            val provider = replying(CLEAN)
            val pipeline = pipeline(provider, cache)
            val events = recording(pipeline)
            val state = pipeline.run(sundown, granted())
            runCurrent()

            assertEquals(1, provider.requests.size)
            assertEquals(CLEAN, assertIs<InsightState.Written>(state.insight).grounded.text)
            assertTrue(events.none { it is PipelineEvent.InsightCached })
            assertEquals(
                listOf(PipelineEvent.SelfRevised(state.normalized.lookupKey, SelfRevision.ASIDE, cached = true)),
                events.filterIsInstance<PipelineEvent.SelfRevised>(),
            )
            assertEquals(listOf(CLEAN), cache.cards.values.toList())
        }

    private companion object {
        // A draft, a comment that cites a line (so `SelfCorrection` leaves it), and the insight again.
        const val REVISED =
            "It is a song from nothing in particular [MB1].\n\n" +
                "Wait, I need to correct that: the line says it is a song [MB1], so I'll keep only that.\n\n" +
                "It is a song [MB1]."
        const val REPEATED =
            "Sundown is a song by Gordon Lightfoot from 1974 [MB1].\n\n" +
                "Sundown is a song by Gordon Lightfoot from 1974 [MB1]."
        const val CLEAN = "It is a song [MB1]."
        const val QUOTED_WAIT = "\"Wait, Let Me Fix It\" is not this song, which came out in 1974 [MB1]."
    }
}
