package app.sleevesight.app

import app.sleevesight.app.session.HttpArt
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.genres.MusicBrainzLabels
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.wiring.SourceKeys
import app.sleevesight.wiring.appPipelineWiring
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `:app` takes the pipeline's network side from `:wiring` as `:core` types, without naming a `:net`
 * type: this compiles without `:net` on the app's compile classpath, so the wiring's surface needs no
 * `:net` type. That `:net` stays off the classpath is `checkModuleGraph`'s to check
 * (`compile-forbidden.app`), not this test's.
 */
class WiringTest {
    private val wiring = appPipelineWiring("0.1.0", JvmAccentFolder, JvmResourceReader, ArtistResults.None)

    // The wiring made its own client; each test closes it, as the app, whose client lives as long as
    // the process, never does.
    @After
    fun closeTheClient() = wiring.close()

    @Test
    fun theAppGetsTheAdaptersResolverLabelsAndProvidersAsCoreTypes() {
        val keys = SourceKeys(lastFm = Secret("lastfm"), web = mapOf(WebEngine.EXA to Secret("exa")))
        val adapters: List<SourceAdapter> = wiring.adapters(keys, Settings())
        val resolver: TrackResolver = wiring.resolver
        val labels: MusicBrainzLabels = wiring.musicBrainzLabels
        val providers: Map<ProviderId, AiProvider> = wiring.aiProviders

        assertEquals(
            listOf(SourceId.MUSICBRAINZ, SourceId.WIKIPEDIA, SourceId.LASTFM, SourceId.WEB),
            adapters.map { it.id },
        )
        assertEquals(setOf(ProviderId.ANTHROPIC, ProviderId.OPENAI), providers.keys)
        assertNotNull(resolver)
        assertNotNull(labels)
    }

    @Test
    fun theArtFetchAdaptsIntoHttpArt() =
        runTest {
            val art = HttpArt { wiring.art(it) }
            // Not an `http` address, so nothing is sent.
            assertNull(art.fetch("content://media/cover"))
        }
}
