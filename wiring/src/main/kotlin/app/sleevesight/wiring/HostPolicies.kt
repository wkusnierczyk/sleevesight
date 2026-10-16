package app.sleevesight.wiring

import app.sleevesight.net.HostPolicy
import app.sleevesight.net.NetConfig
import app.sleevesight.sources.web.ExaWebSearch

/**
 * The one list of host policies (architecture, *Networking*): each adapter's and provider's own
 * rate limit, under its host. A host's limit is in force only once it is in `NetConfig.hosts`, so an
 * adapter or provider with a stated limit adds its policy here, and both processes get it through
 * [netConfig]. `:net`'s defaults hold MusicBrainz's; Exa's is the web search adapter's. The other
 * hosts state no figure for a client ([NetConfig.DEFAULT_HOSTS] says why).
 *
 * For `:cli` and this module only: `:app` never names a `:net` type, and takes its client already
 * made ([appPipelineWiring]).
 */
object HostPolicies {
    /** Every host policy, by domain. */
    val ALL: Map<String, HostPolicy> = NetConfig.DEFAULT_HOSTS + (ExaWebSearch.HOST to ExaWebSearch.HOST_POLICY)

    /**
     * The configuration of the client of a process whose version is [appVersion], for the
     * User-Agent: the app passes its `versionName`, the CLI its own.
     */
    fun netConfig(appVersion: String): NetConfig = NetConfig(appVersion, ALL)
}
