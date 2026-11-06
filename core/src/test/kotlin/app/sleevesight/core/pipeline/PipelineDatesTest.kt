package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.text.APP_DATE_RULE
import app.sleevesight.core.text.DateForm
import app.sleevesight.core.text.US_DATES
import app.sleevesight.core.text.US_DATE_RULE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The insight's prompt asks for dates in the form the parts give (#957), the phone's in the app, and
 * no card's key holds the form: a card written under one is read under another, as written.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineDatesTest {
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")

    private fun TestScope.pipeline(
        provider: FakeProvider,
        cache: PipelineCache,
        dates: () -> DateForm = { DateForm.APP },
    ): Pipeline =
        Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = listOf(FakeAdapter(SourceId.MUSICBRAINZ), wikipedia()),
                resolver = FakeResolver(),
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("sk-test")),
                cache = cache,
                dateForm = dates,
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { Settings(aiProvider = ProviderId.ANTHROPIC) }

    private fun wikipedia() =
        FakeAdapter(SourceId.WIKIPEDIA, byName = 200.milliseconds) { _, _ ->
            SourceResult(Outcome.SongArticle, listOf(line(SourceId.WIKIPEDIA, "Wikipedia line.")), 200.milliseconds)
        }

    @Test
    fun `the insight asks for dates in the parts' form, which its key does not hold`() =
        runTest {
            val cache = MemoryCache()
            val provider = FakeProvider { FakeProvider.done("It is a song [W1].") }
            val us = pipeline(provider, cache) { US_DATES }.run(sundown, write = true)
            assertIs<InsightState.Written>(us.insight)
            val system =
                provider.requests
                    .single()
                    .system
                    .orEmpty()
            assertTrue(US_DATE_RULE in system)
            assertFalse(APP_DATE_RULE in system)

            // The card written on a US phone is the one read on a British phone: no call.
            val british = FakeProvider()
            val app = pipeline(british, cache).run(sundown, write = true)
            assertTrue(assertIs<InsightState.Written>(app.insight).cached)
            assertEquals(0, british.requests.size)
        }

    @Test
    fun `the insight's prompt reads the form when it is sent, not when the run began`() =
        runTest {
            // The phone is British as the track starts, and set to the US while the run awaits a tap.
            var dates = DateForm.APP
            val provider = FakeProvider { FakeProvider.done("It is a song [W1].") }
            val pipeline = pipeline(provider, PipelineCache.None) { dates }
            val changes = MutableStateFlow<TrackChange?>(TrackChange(sundown))
            backgroundScope.launch { pipeline.follow(changes) }
            advanceTimeBy(1.minutes)
            runCurrent()
            assertEquals(InsightState.Idle(canWrite = true), pipeline.state.value!!.insight)
            assertEquals(0, provider.requests.size)

            dates = US_DATES
            pipeline.write()
            advanceTimeBy(1.minutes)
            runCurrent()
            assertIs<InsightState.Written>(pipeline.state.value!!.insight)
            val system =
                provider.requests
                    .single()
                    .system
                    .orEmpty()
            assertTrue(US_DATE_RULE in system)
            assertFalse(APP_DATE_RULE in system)
        }
}
