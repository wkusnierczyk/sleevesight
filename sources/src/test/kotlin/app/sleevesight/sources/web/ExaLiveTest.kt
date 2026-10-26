package app.sleevesight.sources.web

import app.sleevesight.core.Secret
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.sources.Resolution
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One live search, run only when `SLEEVESIGHT_EXA_API_KEY` is set (never in CI). It costs one
 * `lite` search on the key's credits ($0.004).
 */
class ExaLiveTest {
    @Test
    fun `live search finds Sundown`() {
        val key = System.getenv(KEY_VARIABLE)
        assumeTrue(!key.isNullOrBlank(), "$KEY_VARIABLE is not set")
        // `:wiring`'s `HostPolicies` holds the same list, but `:sources` cannot see `:wiring`.
        val config =
            NetConfig(
                appVersion = "0.0.0-test",
                hosts = NetConfig.DEFAULT_HOSTS + (ExaWebSearch.HOST to ExaWebSearch.HOST_POLICY),
            )
        NetClient(config).use { net ->
            val track = Normalizer(JvmAccentFolder).normalize("Sundown", "Gordon Lightfoot")
            val result =
                runBlocking {
                    ExaWebSearch(net, Secret(key!!), JvmAccentFolder)
                        .lookup(track, CompletableDeferred(Resolution.NONE))
                }
            assertEquals(Outcome.SongArticle, result.outcome)
            assertTrue(result.evidence.all { it.url!!.startsWith("http") && it.text.isNotBlank() })
        }
    }

    private companion object {
        const val KEY_VARIABLE = "SLEEVESIGHT_EXA_API_KEY"
    }
}
