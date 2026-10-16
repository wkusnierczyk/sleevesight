package app.sleevesight.wiring

import app.sleevesight.net.NetConfig
import app.sleevesight.sources.web.ExaWebSearch
import kotlin.test.Test
import kotlin.test.assertEquals

class HostPoliciesTest {
    /**
     * Each policy an adapter or provider states, under its host: `:net`'s defaults (MusicBrainz's)
     * and Exa's. A new one is added here and to [HostPolicies.ALL].
     */
    private val stated = NetConfig.DEFAULT_HOSTS + (ExaWebSearch.HOST to ExaWebSearch.HOST_POLICY)

    @Test
    fun `the configuration for any version carries every adapter's and provider's host policy`() {
        for (version in listOf("0.0", "1.4.2")) {
            val config = HostPolicies.netConfig(version)
            for ((host, policy) in stated) {
                assertEquals(policy, config.hosts[host], host)
            }
            assertEquals(stated.keys, config.hosts.keys)
            assertEquals("Sleevesight/$version ( ${NetConfig.CONTACT} )", config.userAgent)
        }
    }

    @Test
    fun `each policy applies to the host its adapter sends to`() {
        val config = HostPolicies.netConfig("0.0")
        val exaHost = ExaWebSearch.ENDPOINT.removePrefix("https://").substringBefore('/')
        assertEquals(ExaWebSearch.HOST, config.domainFor(exaHost))
        assertEquals(NetConfig.MUSICBRAINZ, config.domainFor("musicbrainz.org"))
        assertEquals(HostPolicies.ALL, config.hosts)
    }
}
