package app.sleevesight.wiring

import app.sleevesight.ai.anthropic.AnthropicProvider
import app.sleevesight.ai.google.GoogleProvider
import app.sleevesight.ai.openai.OpenAiProvider
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.BusyWaits
import app.sleevesight.net.NetConfig
import app.sleevesight.sources.lrclib.LrclibClient
import app.sleevesight.sources.web.ExaWebSearch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class HostPoliciesTest {
    /**
     * Each policy an adapter or provider states, under its host: `:net`'s defaults (MusicBrainz's),
     * Exa's, LRCLIB's (#997), Anthropic's, OpenAI's and Google's. No registry of them exists to read this from, so it
     * is a second copy of [HostPolicies.ALL], kept by review like the list itself: a new adapter's
     * policy is added to both. What this test checks on its own is that the configuration for each
     * version carries the list whole, with the version, and that each policy applies to the host its
     * adapter sends to.
     */
    private val stated =
        NetConfig.DEFAULT_HOSTS +
            (ExaWebSearch.HOST to ExaWebSearch.HOST_POLICY) +
            (LrclibClient.HOST to LrclibClient.HOST_POLICY) +
            (AnthropicProvider.HOST to AnthropicProvider.HOST_POLICY) +
            (OpenAiProvider.HOST to OpenAiProvider.HOST_POLICY) +
            (GoogleProvider.HOST to GoogleProvider.HOST_POLICY)

    @Test
    fun `the configuration for any version carries every listed host policy and the version`() {
        for (version in listOf("0.0", "1.4.2")) {
            val config = HostPolicies.netConfig(version)
            for ((host, policy) in stated) {
                // MusicBrainz's carries *Wait when busy* besides (#1120), checked below.
                assertEquals(policy, config.hosts[host]?.copy(busyWait = null), host)
            }
            assertEquals(stated.keys, config.hosts.keys)
            assertSame(HostPolicies.RETRY, config.retry)
            assertEquals("Sleevesight/$version ( ${NetConfig.CONTACT} )", config.userAgent)
        }
    }

    @Test
    fun `each policy applies to the host its adapter sends to`() {
        val config = HostPolicies.netConfig("0.0")
        val exaHost = ExaWebSearch.ENDPOINT.removePrefix("https://").substringBefore('/')
        assertEquals(ExaWebSearch.HOST, config.domainFor(exaHost))
        val lrclibHost = LrclibClient.ENDPOINT.removePrefix("https://").substringBefore('/')
        assertEquals(LrclibClient.HOST, config.domainFor(lrclibHost))
        assertEquals("Sleevesight/0.0 ( https://sleevesight.app )", config.userAgent(config.hosts[lrclibHost]))
        val anthropicHost = AnthropicProvider.BASE_URL.removePrefix("https://").substringBefore('/')
        assertEquals(AnthropicProvider.HOST, config.domainFor(anthropicHost))
        val openAiHost = OpenAiProvider.BASE_URL.removePrefix("https://").substringBefore('/')
        assertEquals(OpenAiProvider.HOST, config.domainFor(openAiHost))
        val googleHost = GoogleProvider.BASE_URL.removePrefix("https://").substringBefore('/')
        assertEquals(GoogleProvider.HOST, config.domainFor(googleHost))
        assertEquals(NetConfig.MUSICBRAINZ, config.domainFor("musicbrainz.org"))
        assertEquals(HostPolicies.ALL.keys, config.hosts.keys)
    }

    @Test
    fun `MusicBrainz's requests draw on their run's allowance, else on one of Wait when busy`() =
        runBlocking {
            var wait = 30.seconds
            val allowance =
                checkNotNull(
                    HostPolicies
                        .netConfig("0.0") { wait }
                        .hosts
                        .getValue(NetConfig.MUSICBRAINZ)
                        .busyWait,
                )
            // Outside a run: an allowance of its own, of the setting as it reads then.
            assertEquals(30.seconds, allowance().left)
            wait = Duration.ZERO
            assertEquals(Duration.ZERO, allowance().left)
            // In a run: the run's, shared.
            val run = BusyWaits(15.seconds)
            withContext(run) {
                allowance().waiting { delay(1) }
                assertEquals(run.left, allowance().left)
                assertTrue(run.left < 15.seconds)
            }
            val default =
                checkNotNull(
                    HostPolicies
                        .netConfig("0.0")
                        .hosts
                        .getValue(NetConfig.MUSICBRAINZ)
                        .busyWait,
                )
            assertEquals(Settings.DEFAULT_MUSICBRAINZ_WAIT, default().left)
        }
}
