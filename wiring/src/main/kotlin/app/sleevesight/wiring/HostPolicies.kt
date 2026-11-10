package app.sleevesight.wiring

import app.sleevesight.ai.anthropic.AnthropicProvider
import app.sleevesight.ai.google.GoogleProvider
import app.sleevesight.ai.openai.OpenAiProvider
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.BusyWaits
import app.sleevesight.net.BusyWait
import app.sleevesight.net.HostPolicy
import app.sleevesight.net.NetConfig
import app.sleevesight.net.RetryPolicy
import app.sleevesight.sources.lrclib.LrclibClient
import app.sleevesight.sources.web.ExaWebSearch
import kotlin.time.Duration

/**
 * The one list of host policies (architecture, *Networking*): each adapter's and provider's own
 * rate limit, under its host. A host's limit is in force only once it is in `NetConfig.hosts`, so an
 * adapter or provider with a stated limit adds its policy here, and both processes get it through
 * [netConfig]. `:net`'s defaults hold MusicBrainz's; Exa's is the web search adapter's; LRCLIB's
 * (#997) its own User-Agent contact and a 429's jitter besides its interval; Anthropic's,
 * OpenAI's and Google's have no interval, only their 429 passed on (`AnthropicProvider.HOST_POLICY`,
 * `OpenAiProvider.HOST_POLICY`, `GoogleProvider.HOST_POLICY`). The other hosts state no figure for a client
 * ([NetConfig.DEFAULT_HOSTS] says why).
 *
 * Nothing finds a new adapter's policy for this list: there is no registry of them to check it
 * against, so the list is kept by review, when an adapter or provider with a stated limit is added.
 *
 * For `:cli` and this module only: `:app` never names a `:net` type, and takes the wiring, with
 * its client inside, from [appPipelineWiring].
 */
object HostPolicies {
    /** Every host policy, by domain. */
    val ALL: Map<String, HostPolicy> =
        NetConfig.DEFAULT_HOSTS +
            (ExaWebSearch.HOST to ExaWebSearch.HOST_POLICY) +
            (LrclibClient.HOST to LrclibClient.HOST_POLICY) +
            (AnthropicProvider.HOST to AnthropicProvider.HOST_POLICY) +
            (OpenAiProvider.HOST to OpenAiProvider.HOST_POLICY) +
            (GoogleProvider.HOST to GoogleProvider.HOST_POLICY)

    /**
     * The retries of the process's client. The AI services' clients retry nothing themselves: the
     * pipeline's `AiRetry` asks an AI call again (#1164).
     */
    val RETRY: RetryPolicy = RetryPolicy()

    /**
     * The configuration of the client of a process whose version is [appVersion], for the
     * User-Agent: the app passes its `versionName`, the CLI its own. A MusicBrainz request it
     * refuses as busy is asked again within the allowance of the run it is made for ([BusyWaits] in
     * its coroutine, *Wait when busy*, #1120), all of the run's requests together; one made outside a
     * run or the next track's preparation (a tracklist, a release group) has an allowance of its own,
     * of [musicBrainzWait] (`Settings.musicBrainzWait`, read then); the CLI keeps the default.
     */
    fun netConfig(
        appVersion: String,
        musicBrainzWait: () -> Duration = { Settings.DEFAULT_MUSICBRAINZ_WAIT },
    ): NetConfig {
        val busyWait: suspend () -> BusyWait = { (BusyWaits.current() ?: BusyWaits(musicBrainzWait())).forNet() }
        val musicBrainz = ALL.getValue(NetConfig.MUSICBRAINZ).copy(busyWait = busyWait)
        return NetConfig(appVersion, ALL + (NetConfig.MUSICBRAINZ to musicBrainz), retry = RETRY)
    }
}

/** A run's allowance for waiting out a busy MusicBrainz, as the client draws on it. */
private fun BusyWaits.forNet(): BusyWait {
    val waits = this
    return object : BusyWait {
        override val left: Duration get() = waits.left

        override suspend fun <T> waiting(block: suspend () -> T): T = waits.waiting(block)
    }
}
