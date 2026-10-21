package app.sleevesight.app.pipeline

import app.sleevesight.app.TestStores
import app.sleevesight.app.secrets.SecretId
import app.sleevesight.app.secrets.SecretStore
import app.sleevesight.app.testCipher
import app.sleevesight.core.Secret
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.wiring.SourceKeys
import app.sleevesight.wiring.appPipelineWiring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The app's pipeline stages from the wiring and the saved keys (#51). Nothing here sends a request. */
class AppPipelinePartsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val stores by lazy { TestStores(tmp.root) }
    private val secrets by lazy { SecretStore(stores.open("secrets"), testCipher()) }
    private val wiring = appPipelineWiring("0.1.0", JvmAccentFolder, JvmResourceReader, ArtistResults.None)
    private val parts = AppPipelineParts(wiring, Normalizer(JvmAccentFolder)) { PipelineCache.None }

    // The wiring made its own client, which each test closes, as `WiringTest` does.
    @After
    fun close() {
        stores.close()
        wiring.close()
    }

    @Test
    fun theKeysAreReadForTheServiceChosenAndTheKeyedSources() =
        runBlocking {
            secrets.set(SecretId.ANTHROPIC, Secret("ai"))
            secrets.set(SecretId.EXA, Secret("exa"))

            val keys = secrets.pipelineKeys(ProviderId.ANTHROPIC)
            assertEquals("ai", keys.ai?.reveal())
            assertNull(keys.sources.lastFm)
            assertEquals("exa", keys.sources.web[WebEngine.EXA]?.reveal())
            assertNull("no service chosen, no AI key", secrets.pipelineKeys(null).ai)
        }

    @Test
    fun aFlowAfterTheAcknowledgementIsNotCollectedBeforeIt() =
        runBlocking {
            val acknowledged = MutableStateFlow(false)
            var collected = false
            val settings =
                flow {
                    collected = true
                    emit(Settings())
                }.afterAcknowledged(acknowledged)
            val first = async(Dispatchers.Default) { settings.first() }
            delay(100)
            assertFalse(collected)

            acknowledged.value = true
            assertEquals(Settings(), withTimeout(5_000) { first.await() })
            assertTrue(collected)
        }

    @Test
    fun keyedSourcesAreLookedUpOnlyWithTheirKeys() {
        val none = parts(PipelineKeys(), Settings())
        assertEquals(listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA), none.adapters.map { it.id })
        assertNotNull(none.musicBrainzLabels)

        val keyed =
            parts(
                PipelineKeys(sources = SourceKeys(Secret("lastfm"), mapOf(WebEngine.EXA to Secret("exa")))),
                Settings(),
            )
        assertEquals(
            listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA, SourceId.LASTFM, SourceId.WEB),
            keyed.adapters.map { it.id },
        )

        val engineOff =
            parts(
                PipelineKeys(sources = SourceKeys(web = mapOf(WebEngine.EXA to Secret("exa")))),
                Settings(webEngines = emptySet()),
            )
        assertEquals(listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA), engineOff.adapters.map { it.id })
    }

    @Test
    fun theAiAccessNeedsTheServiceChosenAndItsKey() {
        val anthropic = Settings(aiProvider = ProviderId.ANTHROPIC)
        assertEquals(ProviderId.ANTHROPIC, parts(PipelineKeys(ai = Secret("ai")), anthropic).ai?.provider?.id)
        assertNull("no key", parts(PipelineKeys(), anthropic).ai)
        assertNull("no service chosen", parts(PipelineKeys(ai = Secret("ai")), Settings()).ai)
        val openAi = Settings(aiProvider = ProviderId.OPENAI)
        assertEquals(ProviderId.OPENAI, parts(PipelineKeys(ai = Secret("ai")), openAi).ai?.provider?.id)
        val google = Settings(aiProvider = ProviderId.GOOGLE)
        assertEquals(ProviderId.GOOGLE, parts(PipelineKeys(ai = Secret("ai")), google).ai?.provider?.id)
        assertNull("no client yet", parts(PipelineKeys(ai = Secret("ai")), Settings(aiProvider = ProviderId.OLLAMA)).ai)
    }
}
