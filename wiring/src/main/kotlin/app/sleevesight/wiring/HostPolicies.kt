package app.sleevesight.wiring

import app.sleevesight.ai.anthropic.AnthropicProvider
import app.sleevesight.ai.google.GoogleProvider
import app.sleevesight.ai.openai.OpenAiProvider
import app.sleevesight.core.settings.Settings
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
     * The retries of the process's client, which a provider that retries a response itself
     * (`AnthropicProvider`, `OpenAiProvider` and `GoogleProvider`, for a 429 passed on) takes too, so both wait alike.
     */
    val RETRY: RetryPolicy = RetryPolicy()

    /**
     * The configuration of the client of a process whose version is [appVersion], for the
     * User-Agent: the app passes its `versionName`, the CLI its own. [musicBrainzWait] is *Wait when
     * busy* (#1120, `Settings.musicBrainzWait`), read for each MusicBrainz request: how long one it
     * refuses as busy is asked again (`HostPolicy.waitWhenBusy`); the CLI keeps the default.
     */
    fun netConfig(
        appVersion: String,
        musicBrainzWait: () -> Duration = { Settings.DEFAULT_MUSICBRAINZ_WAIT },
    ): NetConfig {
        val musicBrainz = ALL.getValue(NetConfig.MUSICBRAINZ).copy(waitWhenBusy = musicBrainzWait)
        return NetConfig(appVersion, ALL + (NetConfig.MUSICBRAINZ to musicBrainz), retry = RETRY)
    }
}
