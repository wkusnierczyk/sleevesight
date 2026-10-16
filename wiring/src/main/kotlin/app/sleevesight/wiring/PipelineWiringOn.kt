package app.sleevesight.wiring

import app.sleevesight.core.ResourceReader
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.net.NetClient

/**
 * The wiring on [net], a client the caller made from [HostPolicies.netConfig] and closes: the CLI's
 * live, replaying or recording client, so the CLI runs the adapters and providers the app builds.
 * Not for `:app`, which cannot name a `:net` type ([appPipelineWiring]).
 */
fun pipelineWiringOn(
    net: NetClient,
    accentFolder: AccentFolder,
    resources: ResourceReader,
): PipelineWiring = PipelineWiring(net, accentFolder, resources)
