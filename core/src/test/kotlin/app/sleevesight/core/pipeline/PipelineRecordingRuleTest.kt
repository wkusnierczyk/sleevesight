package app.sleevesight.core.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.SourceResult
import app.sleevesight.core.synthesis.InsightPrompt
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The key insight of a track whose title leads with its composer is told that a line about another
 * recording of the work is not about this track (#1195), with the track's performers in its `<track>`
 * block, and its card is keyed by its own prompt version; any other track's prompt and key are as before.
 */
class PipelineRecordingRuleTest {
    private val k545 =
        nowPlaying(
            "Mozart: Piano Sonata No. 16 in C Major, K. 545: II. Andante",
            "Wolfgang Amadeus Mozart, Khatia Buniatishvili",
            album = "Mozart: Piano Concertos Nos. 20 & 23",
        )
    private val sundown = nowPlaying("Sundown", "Gordon Lightfoot", album = "Sundown")

    private fun TestScope.pipeline(
        provider: FakeProvider,
        cache: PipelineCache,
    ): Pipeline =
        Pipeline(
            PipelineParts(
                normalizer = Normalizer(JvmAccentFolder),
                adapters = listOf(FakeAdapter(SourceId.MUSICBRAINZ), wikipedia()),
                resolver = FakeResolver(),
                hasher = JvmHasher,
                ai = AiAccess(provider, Secret("sk-test")),
                cache = cache,
            ),
            PipelineTime(timeSource = testTimeSource),
        ) { Settings(aiProvider = ProviderId.ANTHROPIC) }

    private fun wikipedia() =
        FakeAdapter(SourceId.WIKIPEDIA, byName = 200.milliseconds) { _, _ ->
            SourceResult(Outcome.SongArticle, listOf(line(SourceId.WIKIPEDIA, "Wikipedia line.")), 200.milliseconds)
        }

    private suspend fun TestScope.written(
        track: NowPlaying,
        provider: FakeProvider,
        cache: PipelineCache = MemoryCache(),
    ): InsightState.Written = assertIs(pipeline(provider, cache).run(track, write = true).insight)

    @Test
    fun `a composer-led track's insight has the recording rule, its performers and its own version`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("It is a sonata [W1].") }
            val written = written(k545, provider)
            val request = provider.requests.single()
            assertEquals(InsightPrompt.system(allowModel = false, composed = true), request.system)
            assertTrue(InsightPrompt.RECORDING_RULE in request.system.orEmpty())
            assertTrue("\nPerformers: Khatia Buniatishvili\n" in request.prompt)
            assertEquals(InsightPrompt.RECORDING_VERSION, written.writtenBy?.promptVersion)
        }

    @Test
    fun `any other track's insight is asked and keyed as before`() =
        runTest {
            val provider = FakeProvider { FakeProvider.done("It is a song [W1].") }
            val written = written(sundown, provider)
            val request = provider.requests.single()
            assertEquals(InsightPrompt.system(allowModel = false), request.system)
            assertFalse(InsightPrompt.RECORDING_RULE in request.system.orEmpty())
            assertFalse("Performers:" in request.prompt)
            assertEquals(InsightPrompt.VERSION, written.writtenBy?.promptVersion)
        }

    @Test
    fun `a composer-led track's card is kept and read under its own version`() =
        runTest {
            val cache = MemoryCache()
            written(k545, FakeProvider { FakeProvider.done("It is a sonata [W1].") }, cache)
            val again = FakeProvider()
            assertTrue(written(k545, again, cache).cached)
            assertEquals(0, again.requests.size)
        }
}
