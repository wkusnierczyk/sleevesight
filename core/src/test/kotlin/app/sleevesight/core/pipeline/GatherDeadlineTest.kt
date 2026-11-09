package app.sleevesight.core.pipeline

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.model.NormalizedTrack
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.resolution.Lookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.settings.TrackLanguageSearch
import app.sleevesight.core.sources.LanguageAdapter
import app.sleevesight.core.sources.LanguageItems
import app.sleevesight.core.sources.LanguageQuery
import app.sleevesight.core.sources.LanguageSearch
import app.sleevesight.core.sources.Resolution
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.core.sources.SourceResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The gather's deadline (#983, [Pipeline.GATHER_DEADLINE]): a source, the resolution or the search
 * in the track's language that does not answer is cut once the run's lookups have run 20 s, lands
 * unreachable, so a *Retry* or back online asks it again, and the insight is written from what
 * arrived; the time the lookups wait for their grant does not count.
 */
class GatherDeadlineTest {
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")

    // Tamil script names the language, with no request (`TrackLanguage`).
    private val tamil = nowPlaying("கண்ணான கண்ணே", "Sid Sriram")

    // No *Wait when busy* (#1120), which lengthens the deadline: the deadline alone, at 20 s.
    private var settings = Settings(aiProvider = ProviderId.ANTHROPIC, musicBrainzWait = Duration.ZERO)

    private val stalled = 10.minutes

    private fun TestScope.pipeline(
        adapters: List<SourceAdapter>,
        resolver: TrackResolver = FakeResolver(),
        cache: PipelineCache = PipelineCache.None,
        languageSearch: LanguageSearch? = null,
    ): Pipeline =
        Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = adapters,
                resolver = resolver,
                hasher = JvmHasher,
                cache = cache,
                languageSearch = languageSearch,
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { settings }

    private fun TestScope.recording(pipeline: Pipeline): List<PipelineEvent> {
        val events = mutableListOf<PipelineEvent>()
        backgroundScope.launch { pipeline.events.toList(events) }
        runCurrent()
        return events
    }

    private fun TrackState.outcome(source: SourceId): Outcome? = (sources[source] as? SourceState.Done)?.result?.outcome

    private fun web(byName: Duration = Duration.ZERO) = FakeAdapter(SourceId.WEB, byName = byName, verifies = false)

    @Test
    fun `a source that never answers is cut at the deadline, unreachable and retryable, the others kept`() =
        runTest {
            val cache = MemoryCache()
            val pipeline =
                pipeline(
                    listOf(FakeAdapter(SourceId.MUSICBRAINZ), FakeAdapter(SourceId.WIKIPEDIA), web(byName = stalled)),
                    FakeResolver(delay = 1.seconds),
                    cache,
                )
            val events = recording(pipeline)

            val state = pipeline.run(sundown, write = false)

            assertEquals(20.seconds, testScheduler.currentTime.milliseconds)
            assertTrue(state.gathered)
            assertEquals(Outcome.Unreachable, state.outcome(SourceId.WEB))
            assertEquals(Outcome.SongArticle, state.outcome(SourceId.MUSICBRAINZ))
            assertEquals(Outcome.SongArticle, state.outcome(SourceId.WIKIPEDIA))
            assertEquals(listOf("MB1", "W1", "P1"), state.evidence?.ids)
            assertEquals(setOf(SourceId.WEB), Gathered(state.sources, state.resolution).retryable)
            // The answers kept; the cut one not, so the next play asks it again.
            val kept = cache.results.keys.mapTo(mutableSetOf()) { it.first }
            assertEquals(setOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA), kept)
            assertEquals(
                listOf(PipelineEvent.GatherCut(state.normalized!!.lookupKey, listOf(SourceId.WEB), 20.seconds)),
                events.filterIsInstance<PipelineEvent.GatherCut>(),
            )
            assertEquals(
                "deadline   gordon lightfoot|sundown: S cut after 20000 ms",
                EventLine.of(events.filterIsInstance<PipelineEvent.GatherCut>().single()),
            )
        }

    @Test
    fun `MusicBrainz's Wait when busy lengthens the deadline by as much, while MusicBrainz is on`() =
        runTest {
            settings = settings.copy(musicBrainzWait = 30.seconds)
            val pipeline = pipeline(listOf(FakeAdapter(SourceId.MUSICBRAINZ), web()), FakeResolver(delay = stalled))

            val state = pipeline.run(sundown, write = false)

            assertEquals(50.seconds, testScheduler.currentTime.milliseconds)
            assertEquals(Resolution.unreachable(TrackRun.DEADLINE_REACHED), state.resolution)
            assertEquals(50.seconds, Pipeline.gatherDeadline(settings))
            val off = settings.copy(enabledSources = settings.enabledSources - SourceSwitch.MUSICBRAINZ)
            assertEquals(Pipeline.GATHER_DEADLINE, Pipeline.gatherDeadline(off))
        }

    @Test
    fun `a resolution that never answers is cut, and the sources awaiting it are released unreachable`() =
        runTest {
            val musicBrainz = FakeAdapter(SourceId.MUSICBRAINZ)
            val wikipedia = FakeAdapter(SourceId.WIKIPEDIA)
            val pipeline = pipeline(listOf(musicBrainz, wikipedia, web()), FakeResolver(delay = stalled))
            val events = recording(pipeline)

            val state = pipeline.run(sundown, write = false)

            assertEquals(20.seconds, testScheduler.currentTime.milliseconds)
            assertTrue(state.gathered)
            assertEquals(Outcome.Unreachable, state.outcome(SourceId.MUSICBRAINZ))
            assertEquals(Outcome.Unreachable, state.outcome(SourceId.WIKIPEDIA))
            assertEquals(Outcome.SongArticle, state.outcome(SourceId.WEB))
            assertEquals(Resolution.unreachable(TrackRun.DEADLINE_REACHED), state.resolution)
            assertEquals(1, musicBrainz.cancelled)
            assertEquals(
                setOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA),
                Gathered(state.sources, state.resolution).retryable,
            )
            val cut = events.filterIsInstance<PipelineEvent.GatherCut>().single()
            assertEquals(listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA), cut.sources)
            assertTrue(cut.resolution)
            assertEquals("deadline   gordon lightfoot|sundown: resolution, MB, W cut after 20000 ms", EventLine.of(cut))
            val resolved = events.filterIsInstance<PipelineEvent.Resolved>().single()
            assertEquals(Outcome.Unreachable, resolved.outcome)
            assertEquals(TrackRun.DEADLINE_REACHED, resolved.failure)
        }

    /** A lookup in the track's language, answering one line after [wait]. */
    private class LanguageLookup(
        override val id: SourceId,
        private val wait: Duration,
    ) : LanguageAdapter {
        override suspend fun lookup(
            query: LanguageQuery,
            items: LanguageItems,
        ): SourceResult {
            delay(wait)
            val code = query.language.code
            val line =
                EvidenceDraft(id, Scope.SONG, "A line in $code.", "https://example.org/$id", RETRIEVED, language = code)
            return SourceResult(Outcome.SongArticle, listOf(line), wait)
        }
    }

    /** The search in the track's language: Wikidata's language of the work is [work], after [workWait]. */
    private class Search(
        override val wikipedia: LanguageAdapter,
        override val web: LanguageAdapter,
    ) : LanguageSearch {
        var work: List<String> = emptyList()
        var workWait: Duration = Duration.ZERO

        override suspend fun workLanguages(resolution: Resolution): Lookup<List<String>> {
            delay(workWait)
            return Lookup.Found(work)
        }

        override suspend fun releaseLanguages(resolution: Resolution): Lookup<List<String>> = Lookup.Found(emptyList())

        override suspend fun items(query: LanguageQuery): LanguageItems = LanguageItems.NONE
    }

    @Test
    fun `the search in the track's language is cut within the same budget`() =
        runTest {
            settings = settings.copy(trackLanguageSearch = TrackLanguageSearch.ALWAYS)
            val search = Search(LanguageLookup(SourceId.WIKIPEDIA, stalled), LanguageLookup(SourceId.WEB, 1.seconds))
            val pipeline =
                pipeline(
                    listOf(FakeAdapter(SourceId.MUSICBRAINZ), web()),
                    FakeResolver(delay = 5.seconds),
                    languageSearch = search,
                )
            val events = recording(pipeline)

            val state = pipeline.run(tamil, write = false)

            // 5 s in English, then what is left of the 20 s in Tamil.
            assertEquals(20.seconds, testScheduler.currentTime.milliseconds)
            assertTrue(state.gathered)
            assertFalse(state.searchingInLanguage)
            val searched = state.inLanguage!!
            assertEquals("ta", searched.language)
            assertEquals(
                Outcome.Unreachable,
                (searched.sources[SourceId.WIKIPEDIA] as SourceState.Done).result.outcome,
            )
            assertEquals(Outcome.SongArticle, (searched.sources[SourceId.WEB] as SourceState.Done).result.outcome)
            assertEquals(
                setOf(SourceId.WIKIPEDIA),
                Gathered(state.sources, state.resolution, inLanguage = searched).languageRetryable,
            )
            val cut = events.filterIsInstance<PipelineEvent.GatherCut>().single()
            assertEquals(PipelineEvent.GatherCut(cut.key, listOf(SourceId.WIKIPEDIA), 15.seconds, language = "ta"), cut)
            assertEquals("deadline   ${cut.key}: W in ta cut after 15000 ms", EventLine.of(cut))
        }

    @Test
    fun `the time the lookups wait for their grant does not count`() =
        runTest {
            val pipeline = pipeline(listOf(FakeAdapter(SourceId.MUSICBRAINZ), web(byName = stalled)))
            val control = Granted(write = false)
            backgroundScope.launch { pipeline.run(sundown, control) }

            advanceTimeBy(1.minutes)
            runCurrent()
            assertEquals(SourceState.Pending, pipeline.state.value!!.sources[SourceId.MUSICBRAINZ])
            control.grants.getValue(RunStep.LOOKUPS).complete(Unit)

            advanceTimeBy(20.seconds - 1.milliseconds)
            runCurrent()
            assertEquals(SourceState.Pending, pipeline.state.value!!.sources[SourceId.WEB])
            assertFalse(pipeline.state.value!!.gathered)

            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertIs<SourceState.Done>(pipeline.state.value!!.sources[SourceId.WEB])
            assertTrue(pipeline.state.value!!.gathered)
            assertEquals(listOf(RunStep.LOOKUPS), control.ended)
        }

    @Test
    fun `a gather that ends in time is not cut`() =
        runTest {
            val pipeline =
                pipeline(
                    listOf(FakeAdapter(SourceId.MUSICBRAINZ), web(byName = 19.seconds)),
                    FakeResolver(delay = 2.seconds),
                )
            val events = recording(pipeline)

            val state = pipeline.run(sundown, write = false)

            assertEquals(19.seconds, testScheduler.currentTime.milliseconds)
            assertEquals(Outcome.SongArticle, state.outcome(SourceId.WEB))
            assertTrue(events.none { it is PipelineEvent.GatherCut })
        }

    /** An English source answering a line after [wait], or unreachable while [down]. */
    private class Source(
        override val id: SourceId,
    ) : SourceAdapter {
        var wait: Duration = Duration.ZERO
        var down = false

        override suspend fun lookup(
            track: NormalizedTrack,
            resolution: Deferred<Resolution>,
        ): SourceResult {
            delay(wait)
            if (down) return SourceResult(Outcome.Unreachable, emptyList(), wait)
            return SourceResult(Outcome.SongArticle, listOf(line(id, "A line from ${id.tag}.")), wait)
        }
    }

    /** A control that grants every step at once and writes nothing, keeping what the run gathered. */
    private class Kept : RunControl by RunControl.Unattended(write = false) {
        var gathered: Gathered? = null

        override fun gathered(gathered: Gathered) {
            this.gathered = gathered
        }
    }

    private fun TrackState.languageLines(): List<String> =
        evidence!!.lines.filter { it.language != null }.map { "${it.id} ${it.language}" }

    @Test
    fun `cut before the language is decided, a look again keeps what the run before found in it`() =
        runTest {
            settings = settings.copy(trackLanguageSearch = TrackLanguageSearch.ALWAYS)
            val web = Source(SourceId.WEB)
            val search = Search(LanguageLookup(SourceId.WIKIPEDIA, 1.seconds), LanguageLookup(SourceId.WEB, 1.seconds))
            val pipeline = pipeline(listOf(Source(SourceId.MUSICBRAINZ), web), languageSearch = search)
            val first = Kept()
            val before = pipeline.run(tamil, first)
            assertEquals(listOf("W1 ta", "S2 ta"), before.languageLines())

            // The English lookups spend the whole budget: the language's are cut before it is decided.
            web.wait = stalled
            val events = recording(pipeline)
            val state = pipeline.run(tamil, Kept(), lookAgain = LookAgain(first.gathered!!))

            assertTrue(state.gathered)
            assertEquals(before.inLanguage, state.inLanguage)
            assertEquals(listOf("W1 ta", "S2 ta"), state.languageLines())
            assertEquals(
                listOf(
                    PipelineEvent.GatherCut(state.normalized!!.lookupKey, listOf(SourceId.WEB), 20.seconds),
                    PipelineEvent.GatherCut(
                        state.normalized!!.lookupKey,
                        listOf(SourceId.WIKIPEDIA, SourceId.WEB),
                        Duration.ZERO,
                        language = "ta",
                    ),
                ),
                events.filterIsInstance<PipelineEvent.GatherCut>(),
            )
        }

    @Test
    fun `cut before the language is decided, a run asking again keeps what the run before found in it`() =
        runTest {
            settings = settings.copy(trackLanguageSearch = TrackLanguageSearch.ALWAYS)
            val web = Source(SourceId.WEB).apply { down = true }
            val search = Search(LanguageLookup(SourceId.WIKIPEDIA, 1.seconds), LanguageLookup(SourceId.WEB, 1.seconds))
            val pipeline = pipeline(listOf(Source(SourceId.MUSICBRAINZ), web), languageSearch = search)
            val first = Kept()
            val before = pipeline.run(tamil, first)
            assertEquals(listOf("W1 ta", "S1 ta"), before.languageLines())

            // Back online, the web is asked again and stalls through the whole budget.
            web.down = false
            web.wait = stalled
            val again = Kept()
            val state = pipeline.run(tamil, again, again = first.gathered!!)

            assertTrue(state.gathered)
            assertEquals(Outcome.Unreachable, state.outcome(SourceId.WEB))
            assertEquals(before.inLanguage, state.inLanguage)
            assertEquals(listOf("W1 ta", "S1 ta"), state.languageLines())
            assertEquals(emptySet(), again.gathered!!.languageInterrupted)
        }

    @Test
    fun `cut while it decides the language with no language before, the search is asked again`() =
        runTest {
            settings = settings.copy(trackLanguageSearch = TrackLanguageSearch.ALWAYS)
            // Latin letters name no language: Wikidata's language of the work does, and stalls.
            val evidencias = nowPlaying("Evidências", "Chitãozinho & Xororó")
            val search = Search(LanguageLookup(SourceId.WIKIPEDIA, 1.seconds), LanguageLookup(SourceId.WEB, 1.seconds))
            search.workWait = stalled
            val pipeline = pipeline(listOf(Source(SourceId.MUSICBRAINZ), web()), languageSearch = search)
            val events = recording(pipeline)
            val first = Kept()

            val cut = pipeline.run(evidencias, first)

            assertEquals(20.seconds, testScheduler.currentTime.milliseconds)
            assertTrue(cut.gathered)
            assertEquals(null, cut.inLanguage)
            val gathered = first.gathered!!
            assertEquals(setOf(SourceId.WIKIPEDIA, SourceId.WEB), gathered.languageInterrupted)
            // What back online asks again by, with nothing English unreachable.
            assertEquals(emptySet(), gathered.unreachable)
            assertEquals(setOf(SourceId.WIKIPEDIA, SourceId.WEB), gathered.languageRetryable)
            val line = EventLine.of(events.filterIsInstance<PipelineEvent.GatherCut>().single())
            assertEquals("deadline   ${cut.normalized!!.lookupKey}: language search cut after 20000 ms", line)

            // Asked again (back online, or a *Retry*), it searches in the language, and is not marked.
            search.workWait = Duration.ZERO
            search.work = listOf("Q5146")
            val again = Kept()
            val state = pipeline.run(evidencias, again, again = gathered)

            assertEquals("pt", state.inLanguage?.language)
            assertEquals(listOf("W1 pt", "S2 pt"), state.languageLines())
            assertEquals(emptySet(), again.gathered!!.languageInterrupted)
            assertEquals(emptySet(), again.gathered!!.languageRetryable)
        }
}
