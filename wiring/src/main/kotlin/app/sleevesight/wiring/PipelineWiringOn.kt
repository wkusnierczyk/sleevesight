package app.sleevesight.wiring

import app.sleevesight.core.ResourceReader
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.net.NetClient

/**
 * The wiring on [net], a client the caller made from [HostPolicies.netConfig] and closes: the CLI's
 * live, replaying or recording client, so the CLI runs the adapters and providers the app builds.
 * Not for `:app`, which cannot name a `:net` type ([appPipelineWiring]). [artists] is the run's
 * artist cache (#238), none by default.
 */
fun pipelineWiringOn(
    net: NetClient,
    accentFolder: AccentFolder,
    resources: ResourceReader,
    artists: ArtistResults = ArtistResults.None,
): PipelineWiring = PipelineWiring(net, accentFolder, resources, artists = artists)
