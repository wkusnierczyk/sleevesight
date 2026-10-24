package app.sleevesight.wiring

import app.sleevesight.ai.anthropic.AnthropicProvider
import app.sleevesight.ai.google.GoogleProvider
import app.sleevesight.ai.openai.OpenAiProvider
import app.sleevesight.net.NetConfig
import app.sleevesight.sources.web.ExaWebSearch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class HostPoliciesTest {
    /**
     * Each policy an adapter or provider states, under its host: `:net`'s defaults (MusicBrainz's),
     * Exa's, Anthropic's, OpenAI's and Google's. No registry of them exists to read this from, so it
     * is a second copy of [HostPolicies.ALL], kept by review like the list itself: a new adapter's
     * policy is added to both. What this test checks on its own is that the configuration for each
     * version carries the list whole, with the version, and that each policy applies to the host its
     * adapter sends to.
     */
    private val stated =
        NetConfig.DEFAULT_HOSTS +
            (ExaWebSearch.HOST to ExaWebSearch.HOST_POLICY) +
            (AnthropicProvider.HOST to AnthropicProvider.HOST_POLICY) +
            (OpenAiProvider.HOST to OpenAiProvider.HOST_POLICY) +
            (GoogleProvider.HOST to GoogleProvider.HOST_POLICY)

    @Test
    fun `the configuration for any version carries every listed host policy and the version`() {
        for (version in listOf("0.0", "1.4.2")) {
            val config = HostPolicies.netConfig(version)
            for ((host, policy) in stated) {
                assertEquals(policy, config.hosts[host], host)
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
        val anthropicHost = AnthropicProvider.BASE_URL.removePrefix("https://").substringBefore('/')
        assertEquals(AnthropicProvider.HOST, config.domainFor(anthropicHost))
        val openAiHost = OpenAiProvider.BASE_URL.removePrefix("https://").substringBefore('/')
        assertEquals(OpenAiProvider.HOST, config.domainFor(openAiHost))
        val googleHost = GoogleProvider.BASE_URL.removePrefix("https://").substringBefore('/')
        assertEquals(GoogleProvider.HOST, config.domainFor(googleHost))
        assertEquals(NetConfig.MUSICBRAINZ, config.domainFor("musicbrainz.org"))
        assertEquals(HostPolicies.ALL, config.hosts)
    }
}
