package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.ReasoningEffort
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
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.SentenceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PipelineTest {
    private val normalizer = Normalizer(JvmAccentFolder)
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")
    private val snowbird = nowPlaying("Snowbird", "Anne Murray")

    private var settings = Settings(aiProvider = ProviderId.ANTHROPIC)

    private fun TestScope.pipeline(
        adapters: List<SourceAdapter>,
        resolver: TrackResolver = FakeResolver(),
        provider: FakeProvider? = FakeProvider(),
        cache: PipelineCache = PipelineCache.None,
    ): Pipeline =
        Pipeline(
            PipelineParts(
                normalizer = normalizer,
                adapters = adapters,
                resolver = resolver,
                hasher = JvmHasher,
                ai = provider?.let { AiAccess(it, Secret("sk-test")) },
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

    private fun mb() = FakeAdapter(SourceId.MUSICBRAINZ)

    // Background work (the follow loop, the event collector) is not run by `advanceUntilIdle`.
    private fun TestScope.settle() {
        advanceTimeBy(1.minutes)
        runCurrent()
    }

    private fun wikipedia(byName: Duration = 200.milliseconds) =
        FakeAdapter(SourceId.WIKIPEDIA, byName = byName) { _, resolved ->
            val lines =
                listOf(line(SourceId.WIKIPEDIA, "Wikipedia line."), line(SourceId.WIKIDATA, "Wikidata line."))
            if (resolved?.resolved == true) {
                SourceResult(Outcome.SongArticle, lines, byName)
            } else {
                SourceResult(Outcome.SongArticle, emptyList(), byName, unverified = lines)
            }
        }

    @Test
    fun `sources run by name in parallel, with resolution alongside, and verify when it lands`() =
        runTest {
            val wikipedia = wikipedia(byName = 200.milliseconds)
            val web = FakeAdapter(SourceId.WEB, byName = 300.milliseconds, verifies = false)
            val pipeline = pipeline(listOf(mb(), wikipedia, web), FakeResolver(delay = 1.seconds))

            val state = pipeline.run(sundown, write = false)

            // In parallel: the slowest part, resolution, bounds the run; nothing waited for another.
            assertEquals(1.seconds, testScheduler.currentTime.milliseconds)
            assertEquals(listOf("gordon lightfoot|sundown"), wikipedia.asked)
            assertTrue(wikipedia.resolutions.single().resolved)
            assertEquals(listOf("MB1", "W1", "WD1", "G1", "P1"), state.evidence?.ids)
            assertTrue(state.gathered)
        }

    @Test
    fun `the state updates as each source lands`() =
        runTest {
            val pipeline =
                pipeline(
                    listOf(mb(), FakeAdapter(SourceId.WEB, byName = 300.milliseconds, verifies = false)),
                    FakeResolver(delay = 1.seconds),
                )
            launch { pipeline.run(sundown, write = false) }

            advanceTimeBy(301.milliseconds)
            val partial = pipeline.state.value!!
            assertIs<SourceState.Done>(partial.sources[SourceId.WEB])
            assertEquals(SourceState.Pending, partial.sources[SourceId.MUSICBRAINZ])
            assertNull(partial.evidence)

            advanceUntilIdle()
            assertIs<SourceState.Done>(pipeline.state.value!!.sources[SourceId.MUSICBRAINZ])
        }

    @Test
    fun `ids do not depend on the order the sources land in`() =
        runTest {
            fun run(webFirst: Boolean): List<String> {
                val web = FakeAdapter(SourceId.WEB, byName = if (webFirst) 10.milliseconds else 500.milliseconds)
                val wiki = wikipedia(byName = if (webFirst) 500.milliseconds else 10.milliseconds)
                var ids: List<String> = emptyList()
                launch { ids = pipeline(listOf(web, wiki)).run(sundown, write = false).evidence!!.ids }
                advanceUntilIdle()
                return ids
            }
            assertEquals(run(webFirst = true), run(webFirst = false))
        }

    @Test
    fun `a source switched off is source_off, asked nothing, and its lines are not cited`() =
        runTest {
            settings = settings.copy(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.WIKIPEDIA)
            val wikipedia = wikipedia()
            val state = pipeline(listOf(mb(), wikipedia)).run(sundown, write = false)

            assertEquals(Outcome.SourceOff, (state.sources[SourceId.WIKIPEDIA] as SourceState.Done).result.outcome)
            assertEquals(emptyList(), wikipedia.asked)
            assertTrue(state.evidence!!.lines.none { it.source == SourceId.WIKIDATA })
        }

    @Test
    fun `MusicBrainz off means no resolution, and the others get none`() =
        runTest {
            settings = settings.copy(enabledSources = Settings.DEFAULT_SOURCES - SourceSwitch.MUSICBRAINZ)
            val resolver = FakeResolver()
            val wikipedia = wikipedia()
            val state = pipeline(listOf(mb(), wikipedia), resolver).run(sundown, write = false)

            assertEquals(0, resolver.calls)
            assertEquals(listOf(Resolution.NONE), wikipedia.resolutions)
            val wiki = (state.sources[SourceId.WIKIPEDIA] as SourceState.Done).result
            assertEquals(2, wiki.unverified.size)
        }

    @Test
    fun `a failing resolution still completes the adapters' resolution, with none`() =
        runTest {
            val wikipedia = wikipedia()
            val pipeline = pipeline(listOf(wikipedia), FakeResolver(failure = IllegalStateException("bug")))
            val events = recording(pipeline)

            val state = pipeline.run(sundown, write = false)

            assertEquals(listOf(Resolution.NONE), wikipedia.resolutions)
            assertTrue(state.gathered)
            runCurrent()
            assertTrue(events.any { it is PipelineEvent.SourceFailed && it.reason == "IllegalStateException" })
        }

    @Test
    fun `a failing adapter is failed, and the others land`() =
        runTest {
            val broken = FakeAdapter(SourceId.LASTFM, failure = IllegalArgumentException("parse"))
            val state = pipeline(listOf(mb(), broken)).run(sundown, write = false)

            assertEquals(SourceState.Failed("IllegalArgumentException"), state.sources[SourceId.LASTFM])
            assertIs<SourceState.Done>(state.sources[SourceId.MUSICBRAINZ])
            assertEquals(listOf("MB1", "P1"), state.evidence!!.ids)
        }

    @Test
    fun `the player's release is a P line`() =
        runTest {
            val state = pipeline(emptyList()).run(sundown, write = false)
            val p1 = state.evidence!!["P1"]!!
            assertEquals("This recording is on the release \"Sundown\".", p1.text)
            assertEquals(Scope.RELEASE, p1.scope)
        }

    @Test
    fun `the cache is consulted first, and a hit needs neither the source nor the resolution`() =
        runTest {
            val cache = MemoryCache()
            val resolver = FakeResolver()
            val wikipedia = wikipedia()
            val provider = FakeProvider { FakeProvider.done("It is a song [W1].") }
            val pipeline = pipeline(listOf(mb(), wikipedia), resolver, provider, cache)
            val events = recording(pipeline)

            val first = pipeline.run(sundown, write = true)
            val second = pipeline.run(sundown, write = true)

            assertEquals(1, wikipedia.asked.size)
            assertEquals(1, resolver.calls)
            assertEquals(1, provider.requests.size)
            assertEquals(first.evidence?.fingerprint, second.evidence?.fingerprint)
            assertTrue((second.sources[SourceId.WIKIPEDIA] as SourceState.Done).cached)
            val insight = assertIs<InsightState.Written>(second.insight)
            assertTrue(insight.cached)
            runCurrent()
            assertTrue(events.any { it is PipelineEvent.InsightCached })
            assertEquals(2, events.count { it is PipelineEvent.SourceLanded && it.cached })
        }

    @Test
    fun `a result verified against a resolution that could not be made is not cached`() =
        runTest {
            val cache = MemoryCache()
            val unreachable = FakeResolver(resolution = Resolution.UNREACHABLE)
            val pipeline = pipeline(listOf(wikipedia()), unreachable, cache = cache)
            val events = recording(pipeline)

            pipeline.run(sundown, write = false)

            assertEquals(emptyMap(), cache.results)
            runCurrent()
            assertTrue(events.any { it is PipelineEvent.NotCached && it.source == SourceId.WIKIPEDIA })
        }

    @Test
    fun `a provisional result, left unverified by a failed request, is shown and not cached`() =
        runTest {
            val cache = MemoryCache()
            val lastFm =
                FakeAdapter(SourceId.LASTFM) { _, _ ->
                    val lines = listOf(line(SourceId.LASTFM, "Last.fm line."))
                    SourceResult(Outcome.ArtistArticle, emptyList(), Duration.ZERO, lines, provisional = true)
                }
            val pipeline = pipeline(listOf(mb(), lastFm), cache = cache)
            val events = recording(pipeline)

            val state = pipeline.run(sundown, write = false)

            val shown = assertIs<SourceState.Done>(state.sources[SourceId.LASTFM]).result
            assertEquals(listOf("Last.fm line."), shown.unverified.map { it.text })
            assertEquals(
                setOf(SourceId.MUSICBRAINZ),
                cache.results.keys
                    .map { it.first }
                    .toSet(),
            )
            runCurrent()
            assertTrue(
                events.any {
                    it is PipelineEvent.NotCached && it.source == SourceId.LASTFM && it.reason == TrackRun.PROVISIONAL
                },
            )
        }

    @Test
    fun `the player's length is part of the cache key, since resolution chooses the recording by it`() =
        runTest {
            val cache = MemoryCache()
            val resolver = FakeResolver()
            val pipeline = pipeline(listOf(mb()), resolver, cache = cache)

            pipeline.run(sundown.copy(duration = 180.seconds), write = false)
            pipeline.run(sundown.copy(duration = 300.seconds), write = false)
            pipeline.run(sundown.copy(duration = 180.seconds), write = false)

            assertEquals(2, resolver.calls)
        }

    @Test
    fun `a raised effort writes afresh rather than serving the card written at the old one`() =
        runTest {
            val cache = MemoryCache()
            val provider = FakeProvider()
            val pipeline = pipeline(listOf(mb()), provider = provider, cache = cache)

            pipeline.run(sundown, write = true)
            settings = settings.copy(aiEffort = ReasoningEffort.HIGH)
            pipeline.run(sundown, write = true)
            pipeline.run(sundown, write = true)

            assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), provider.requests.map { it.effort })
        }

    @Test
    fun `a failing cache is a miss, and the run goes on`() =
        runTest {
            val cache = MemoryCache().apply { failing = true }
            val pipeline = pipeline(listOf(wikipedia()), cache = cache)
            val events = recording(pipeline)

            val state = pipeline.run(sundown, write = false)

            assertTrue(state.gathered)
            runCurrent()
            assertTrue(events.any { it is PipelineEvent.CacheFailed })
        }

    @Test
    fun `the insight is written from the evidence, checked, and asked at the effort setting`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("It is a song [MB1]. It charted [W9].") }
            val pipeline = pipeline(listOf(mb()), provider = provider)
            val events = recording(pipeline)

            val state = pipeline.run(sundown, write = true)

            val written = assertIs<InsightState.Written>(state.insight)
            assertEquals(1, written.grounded.counts.ok)
            assertEquals(1, written.grounded.counts.unknown)
            assertEquals(
                SentenceStatus.UNKNOWN,
                written.grounded.paragraphs
                    .single()[1]
                    .status,
            )
            val request = provider.requests.single()
            assertEquals("claude-haiku-5-5", request.model)
            assertEquals(ReasoningEffort.LOW, request.effort)
            assertTrue("[MB1] (MusicBrainz, song) A line from MB." in request.prompt)
            runCurrent()
            assertTrue(events.any { it is PipelineEvent.Grounded && it.counts.unknown == 1 })
            assertTrue(events.any { it is PipelineEvent.ModelDone })
        }

    @Test
    fun `no evidence means no call`() =
        runTest {
            val provider = FakeProvider()
            val empty = FakeAdapter(SourceId.WEB) { _, _ -> SourceResult(Outcome.None, emptyList(), 0.seconds) }
            val state = pipeline(listOf(empty), provider = provider).run(snowbird, write = true)

            assertEquals(InsightState.NoEvidence, state.insight)
            assertEquals(0, provider.requests.size)
        }

    @Test
    fun `NO_EVIDENCE, an error, a refusal and a cut-off reply each show as such`() =
        runTest {
            val cases =
                listOf(
                    FakeProvider.done("NO_EVIDENCE") to InsightState.NoEvidence,
                    listOf(CompletionEvent.Error(AiErrorKind.UNAUTHORIZED)) to InsightState.Failed("unauthorized"),
                    FakeProvider.done("No.", StopReason.REFUSAL) to InsightState.Failed("declined"),
                )
            for ((reply, expected) in cases) {
                val state = pipeline(listOf(mb()), provider = FakeProvider { reply }).run(sundown, write = true)
                assertEquals(expected, state.insight)
            }
            val cache = MemoryCache()
            val cut = FakeProvider { FakeProvider.done("It is a song [MB1]", StopReason.MAX_TOKENS) }
            val state = pipeline(listOf(mb()), provider = cut, cache = cache).run(sundown, write = true)
            assertEquals(false, assertIs<InsightState.Written>(state.insight).complete)
            assertEquals(emptyMap(), cache.cards)
        }

    @Test
    fun `with insights off, or no AI service, nothing is written`() =
        runTest {
            val provider = FakeProvider()
            settings = settings.copy(writeInsights = false)
            assertEquals(
                InsightState.Idle(canWrite = false),
                pipeline(listOf(mb()), provider = provider).run(sundown, write = true).insight,
            )
            settings = settings.copy(writeInsights = true)
            assertEquals(
                InsightState.Idle(canWrite = false),
                pipeline(listOf(mb()), provider = null).run(sundown, write = true).insight,
            )
            assertEquals(0, provider.requests.size)
        }

    @Test
    fun `model knowledge on lets the prompt and the check allow M`() =
        runTest {
            settings = settings.copy(enabledSources = Settings.DEFAULT_SOURCES + SourceSwitch.MODEL)
            val provider = FakeProvider { FakeProvider.done("It is a song [MB1]. Lightfoot was Canadian [M].") }
            val state = pipeline(listOf(mb()), provider = provider).run(sundown, write = true)

            assertEquals(1, assertIs<InsightState.Written>(state.insight).grounded.counts.model)
            assertTrue("[M]" in provider.requests.single().prompt)
        }

    // follow: the app's loop over track changes.

    @Test
    fun `a change waits for the debounce, and only the last of a burst runs`() =
        runTest {
            val mb = mb()
            val pipeline = pipeline(listOf(mb))
            val changes = MutableStateFlow<TrackChange?>(null)
            backgroundScope.launch { pipeline.follow(changes) }

            changes.value = TrackChange(sundown)
            advanceTimeBy(300.milliseconds)
            changes.value = TrackChange(snowbird)
            advanceTimeBy(Pipeline.DEBOUNCE - 1.milliseconds)
            assertEquals(emptyList(), mb.asked)

            advanceTimeBy(2.milliseconds)
            runCurrent()
            assertEquals(listOf("anne murray|snowbird"), mb.asked)
        }

    @Test
    fun `a new track cancels the superseded one's run`() =
        runTest {
            val slow = FakeAdapter(SourceId.WEB, byName = 10.seconds, verifies = false)
            val pipeline = pipeline(listOf(slow))
            val events = recording(pipeline)
            val changes = MutableStateFlow<TrackChange?>(TrackChange(sundown))
            backgroundScope.launch { pipeline.follow(changes) }

            advanceTimeBy(2.seconds)
            changes.value = TrackChange(snowbird)
            settle()

            assertEquals(1, slow.cancelled)
            assertEquals(
                "Snowbird",
                pipeline.state.value!!
                    .normalized.title,
            )
            assertTrue(pipeline.state.value!!.gathered)
            runCurrent()
            assertTrue(events.any { it is PipelineEvent.Superseded && it.key == "gordon lightfoot|sundown" })
        }

    @Test
    fun `no session neither cancels nor clears, and the same metadata is not run again`() =
        runTest {
            val mb = mb()
            val pipeline = pipeline(listOf(mb))
            val changes = MutableStateFlow<TrackChange?>(TrackChange(sundown))
            backgroundScope.launch { pipeline.follow(changes) }
            settle()

            changes.value = null
            settle()
            changes.value = TrackChange(sundown.copy(artUri = "content://art/2"))
            settle()

            assertEquals(1, mb.asked.size)
            assertEquals(
                "Sundown",
                pipeline.state.value!!
                    .normalized.title,
            )
        }

    @Test
    fun `a length that differs only in milliseconds does not restart the run`() =
        runTest {
            val mb = mb()
            val pipeline = pipeline(listOf(mb))
            val changes = MutableStateFlow<TrackChange?>(TrackChange(sundown.copy(duration = 213.seconds)))
            backgroundScope.launch { pipeline.follow(changes) }
            settle()

            changes.value = TrackChange(sundown.copy(duration = 213.seconds + 420.milliseconds))
            settle()

            assertEquals(1, mb.asked.size)
        }

    @Test
    fun `another release, credit or length of the same song is run again`() =
        runTest {
            val mb = mb()
            val pipeline = pipeline(listOf(mb))
            val changes = MutableStateFlow<TrackChange?>(TrackChange(sundown))
            backgroundScope.launch { pipeline.follow(changes) }
            settle()

            val otherRelease = sundown.copy(album = "Gord's Gold")
            changes.value = TrackChange(otherRelease)
            settle()
            val p1 = pipeline.state.value!!.evidence!!["P1"]!!
            assertEquals("This recording is on the release \"Gord's Gold\".", p1.text)

            val featuring = otherRelease.copy(title = "Sundown (feat. Guest)")
            changes.value = TrackChange(featuring)
            settle()
            assertEquals(
                listOf("Gordon Lightfoot", "Guest"),
                pipeline.state.value!!
                    .normalized.artists,
            )

            changes.value = TrackChange(featuring.copy(duration = 300.seconds))
            settle()
            assertEquals(4, mb.asked.size)
        }

    @Test
    fun `nothing is written before the user's first action, then every track is (the default)`() =
        runTest {
            val provider = FakeProvider()
            val pipeline = pipeline(listOf(mb()), provider = provider)
            val changes = MutableStateFlow<TrackChange?>(TrackChange(sundown))
            backgroundScope.launch { pipeline.follow(changes) }
            settle()

            assertEquals(InsightState.Idle(canWrite = true), pipeline.state.value!!.insight)
            assertEquals(0, provider.requests.size)

            pipeline.write()
            settle()
            assertIs<InsightState.Written>(pipeline.state.value!!.insight)

            changes.value = TrackChange(snowbird)
            settle()
            assertIs<InsightState.Written>(pipeline.state.value!!.insight)
            assertEquals(2, provider.requests.size)
        }

    @Test
    fun `when I skip writes the tracks the user moved to, and waits for a tap on the others`() =
        runTest {
            settings = settings.copy(writeAutomatically = WriteAutomatically.WHEN_I_SKIP)
            val provider = FakeProvider()
            val pipeline = pipeline(listOf(mb()), provider = provider)
            pipeline.userActed()
            val changes = MutableStateFlow<TrackChange?>(TrackChange(sundown, ChangeCause.NATURAL_END))
            backgroundScope.launch { pipeline.follow(changes) }
            settle()
            assertEquals(InsightState.Idle(canWrite = true), pipeline.state.value!!.insight)

            changes.value = TrackChange(snowbird, ChangeCause.SKIP_IN_PLAYER)
            settle()
            assertIs<InsightState.Written>(pipeline.state.value!!.insight)
            assertEquals(1, provider.requests.size)
        }

    // The gate (D6 in the app): no request starts while it is closed.

    @Test
    fun `while requests are not allowed, a change waits before its lookups`() =
        runTest {
            val mb = mb()
            val pipeline = pipeline(listOf(mb))
            val allowed = MutableStateFlow(false)
            val changes = MutableStateFlow<TrackChange?>(TrackChange(sundown))
            backgroundScope.launch { pipeline.follow(changes, allowed) }
            settle()
            assertEquals(emptyList(), mb.asked)

            allowed.value = true
            settle()
            assertEquals(listOf("gordon lightfoot|sundown"), mb.asked)
        }

    @Test
    fun `lookups under way finish when requests stop being allowed, and the model call waits`() =
        runTest {
            val provider = FakeProvider()
            val slow = FakeAdapter(SourceId.MUSICBRAINZ, byName = 5.seconds)
            val pipeline = pipeline(listOf(slow), provider = provider)
            pipeline.userActed()
            val allowed = MutableStateFlow(true)
            val changes = MutableStateFlow<TrackChange?>(TrackChange(sundown))
            backgroundScope.launch { pipeline.follow(changes, allowed) }
            advanceTimeBy(Pipeline.DEBOUNCE + 1.seconds)
            allowed.value = false
            settle()
            assertTrue(pipeline.state.value!!.gathered)
            assertEquals(0, slow.cancelled)
            assertEquals(0, provider.requests.size)

            allowed.value = true
            settle()
            assertEquals(1, provider.requests.size)
        }

    @Test
    fun `after the app opens again, a run waiting to write waits for an action`() =
        runTest {
            val provider = FakeProvider()
            val pipeline = pipeline(listOf(mb()), provider = provider)
            pipeline.userActed()
            val allowed = MutableStateFlow(true)
            val changes = MutableStateFlow<TrackChange?>(null)
            backgroundScope.launch { pipeline.follow(changes, allowed) }
            runCurrent()
            allowed.value = false
            changes.value = TrackChange(sundown)
            settle()

            pipeline.appOpened()
            allowed.value = true
            settle()
            assertEquals(InsightState.Idle(canWrite = true), pipeline.state.value!!.insight)
            assertEquals(0, provider.requests.size)

            pipeline.write()
            settle()
            assertEquals(1, provider.requests.size)
        }

    @Test
    fun `a replay is never written without a tap`() =
        runTest {
            val provider = FakeProvider()
            val pipeline = pipeline(listOf(mb()), provider = provider)
            pipeline.userActed()
            backgroundScope.launch { pipeline.follow(MutableStateFlow(TrackChange(sundown, ChangeCause.REPLAY))) }
            settle()
            assertEquals(InsightState.Idle(canWrite = true), pipeline.state.value!!.insight)
            assertEquals(0, provider.requests.size)
        }

    @Test
    fun `a report is current when the state is about it in everything a run depends on`() =
        runTest {
            val pipeline = pipeline(listOf(mb()))
            assertEquals(false, pipeline.isCurrent(sundown))
            backgroundScope.launch { pipeline.follow(MutableStateFlow(TrackChange(sundown))) }
            settle()

            assertTrue(pipeline.isCurrent(sundown.copy(artUri = "content://art/2")))
            assertEquals(false, pipeline.isCurrent(sundown.copy(album = "Gord's Gold")))
            assertEquals(false, pipeline.isCurrent(snowbird))
        }
}
