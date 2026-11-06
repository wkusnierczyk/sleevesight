package app.sleevesight.app.pipeline

import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiErrorKind
import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WriteAutomatically
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.wiring.appPipelineWiring
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The key insight while the selected model is found unavailable (#540, #911), through the app's own
 * rebuild: `ModelCheck` marks the model, `rebuilds` rebuilds the pipeline, and `AppPipelineParts`
 * builds it with no AI access. Nothing is written then, but what is kept still shows: the insight
 * kept under the model selected first, else another model's, as written before, with nothing
 * offered; with nothing kept, nothing, as before. Until the rebuild publishes, the card is given the
 * call that failed (#969), which it says as the model unavailable. The stages that would send requests
 * are the fixture's fakes; the AI access is the app's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PipelineHostUnavailableTest : PipelineHostFixture() {
    // The app's wiring, every AI service through [provider]; nothing here sends a request.
    private val wiring =
        appPipelineWiring("0.1.0", JvmAccentFolder, JvmResourceReader, ArtistResults.None, aiThrough = { provider })

    private lateinit var check: ModelCheck

    // The process the host runs in, cancelled for a cold start.
    private var process: Job? = null

    @After
    fun close() = wiring.close()

    // The fixture's stages, with the AI access as the app's parts build it, through [check].
    private fun appParts(): (PipelineKeys, Settings) -> PipelineParts {
        val app = AppPipelineParts(wiring, Normalizer(JvmAccentFolder), models = check) { cache }
        return { keys, built ->
            val fake = fakeParts(keys, built)
            val real = app(keys, built)
            PipelineParts(
                fake.normalizer,
                fake.adapters,
                fake.resolver,
                fake.hasher,
                real.ai,
                fake.cache,
                modelUnavailable = real.modelUnavailable,
            )
        }
    }

    // A host on the app's parts, rebuilt as the app's is when a model is found unavailable, started
    // in a process of its own.
    private fun TestScope.startProcess() {
        if (!::check.isInitialized) check = ModelCheck(wiring.aiProviders, { Secret("ai") }, backgroundScope)
        process?.cancel()
        runCurrent()
        val job = Job(backgroundScope.coroutineContext[Job])
        process = job
        host = newHost(appParts(), check.rebuilds(settings))
        host.activityStarted()
        host.start(CoroutineScope(backgroundScope.coroutineContext + job))
        runCurrent()
    }

    private fun model(id: String) {
        settings.value = settings.value.withModel(ProviderId.ANTHROPIC, id)
    }

    // [id] found unavailable, as a call the service answered that it does not know it would mark it.
    private suspend fun refused(id: String) {
        val refusal = CompletionEvent.Error(AiErrorKind.MODEL_NOT_FOUND)
        check.answered(check.ticket(), ProviderId.ANTHROPIC, id, Secret("ai"), refusal)
        check.unavailable.first { it[ProviderId.ANTHROPIC]?.model == id }
    }

    private fun shown(): InsightState = checkNotNull(host.state.value.track).insight

    private fun writtenBy(): String? = (shown() as? InsightState.Written)?.writtenBy?.model

    // Nothing offered beside what is shown: no Write, no Rewrite.
    private fun assertNothingOffered() {
        assertFalse(host.state.value.rewrite)
        assertNull(host.state.value.writeKey)
    }

    @Test
    fun `on a later play, another model's insight kept shows with nothing offered`() =
        runTest {
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.NEVER)
            model(FIRST)
            startProcess()
            play("Alpha")
            settle()
            tap()
            settle()
            assertEquals(FIRST, writtenBy())

            // The other model chosen, and its rewrite refused: the model is marked, the pipeline rebuilt.
            model(OTHER)
            settle()
            provider.error = AiErrorKind.MODEL_NOT_FOUND
            tap()
            settle()
            check.unavailable.first { it[ProviderId.ANTHROPIC]?.model == OTHER }
            settle()
            assertEquals(FIRST, writtenBy())
            assertNothingOffered()

            // A later play: another track, then the track again.
            play("Beta")
            settle()
            play("Alpha")
            settle()
            assertEquals(FIRST, writtenBy())
            assertTrue((shown() as InsightState.Written).cached)
            assertNothingOffered()
            assertEquals(2, provider.requestsFor("Alpha"))
        }

    // [id] refused by the service, and found unavailable, while the pipeline's rebuild without AI access
    // has not published yet: its keys still being read, as a phone's lookups take time to (#969).
    private suspend fun TestScope.refusedBeforeTheRebuild(
        id: String,
        write: () -> Unit,
    ): CompletableDeferred<Unit> {
        val reading = CompletableDeferred<Unit>()
        keysReady = reading
        provider.error = AiErrorKind.MODEL_NOT_FOUND
        write()
        runCurrent()
        check.unavailable.first { it[ProviderId.ANTHROPIC]?.model == id }
        settle()
        return reading
    }

    @Test
    fun `under On demand, a rewrite refused shows the insight kept with why until the rebuild shows it alone`() =
        runTest {
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.NEVER)
            model(FIRST)
            startProcess()
            play("Alpha")
            settle()
            tap()
            settle()
            model(OTHER)
            settle()

            // The model found unavailable, the rebuild not yet published (#969): the insight kept, with
            // why its rewrite failed, which the card leaves out while it says the model is unavailable.
            val reading = refusedBeforeTheRebuild(OTHER) { tap() }
            assertEquals(FIRST, writtenBy())
            assertTrue(host.state.value.cardShown)
            assertEquals(MODEL_NOT_FOUND, host.state.value.rewriteFailed)
            assertNothingOffered()

            reading.complete(Unit)
            settle()
            assertEquals(FIRST, writtenBy())
            assertNull(host.state.value.rewriteFailed)
            assertNothingOffered()
        }

    @Test
    fun `automatically, a write anew refused on a later play fails until the rebuild shows the insight kept`() =
        runTest {
            model(FIRST)
            startProcess()
            play("Alpha")
            settle()
            tap()
            settle()
            play("Beta")
            settle()
            model(OTHER)
            settle()

            // A later play of the track, written anew by itself and refused: the failure, which the card
            // says as the model unavailable in its place, until the rebuild shows the insight kept (#969).
            val reading = refusedBeforeTheRebuild(OTHER) { play("Alpha") }
            assertEquals(InsightState.Failed(MODEL_NOT_FOUND), shown())
            assertNull(host.state.value.rewriteFailed)

            reading.complete(Unit)
            settle()
            assertEquals(FIRST, writtenBy())
            assertTrue((shown() as InsightState.Written).cached)
            assertNull(host.state.value.rewriteFailed)
            assertNothingOffered()
            assertEquals(2, provider.requestsFor("Alpha"))
        }

    @Test
    fun `after a cold start, the insight kept under the model selected shows first`() =
        runTest {
            settings.value = settings.value.copy(writeAutomatically = WriteAutomatically.NEVER)
            model(FIRST)
            startProcess()
            play("Alpha")
            settle()
            tap()
            settle()
            model(OTHER)
            settle()
            tap()
            settle()
            assertEquals(OTHER, writtenBy())

            // The other model found unavailable later, then a new process: its own insight, not the
            // first model's, though nothing can be written with it.
            refused(OTHER)
            startProcess()
            play("Alpha")
            settle()
            assertEquals(OTHER, writtenBy())
            assertNothingOffered()
            assertEquals(2, provider.requestsFor("Alpha"))
        }

    @Test
    fun `with nothing kept, nothing is shown, as before`() =
        runTest {
            model(FIRST)
            startProcess()
            refused(FIRST)
            settle()
            play("Alpha")
            settle()
            assertEquals(InsightState.Idle(canWrite = false), shown())
            assertNothingOffered()
            assertEquals(0, provider.requests)
        }

    private companion object {
        const val FIRST = "claude-first"
        const val OTHER = "claude-other"
        const val MODEL_NOT_FOUND = "model_not_found"
    }
}
