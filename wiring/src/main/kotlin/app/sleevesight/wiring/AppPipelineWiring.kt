package app.sleevesight.wiring

import app.sleevesight.core.ResourceReader
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.net.NetClient

/**
 * The app's wiring, on the process's one client, which this creates from [HostPolicies.netConfig]
 * with the app's [appVersion] (its `versionName`). Called once per process, by `AppGraph` (#51):
 * the rate limiters belong to the client, so a second wiring would double every limit. The client
 * lives as long as the process; [PipelineWiring.close] closes it, for tests. MusicBrainz's answers
 * are kept for a short time ([PipelineWiring], `RememberedMusicBrainz`), for preparing the next
 * track (D13). What sources give for an artist is kept in [artists], the app's artist cache (#238).
 * [genres] is whether genre information is asked for (*Show genres*, #444), as last read.
 *
 * Its parameters and result are `:core` types and plain values, so `:app` calls it without `:net`
 * on its compile classpath. Kept apart from [pipelineWiringOn], which takes a `:net` type: no
 * overload, supertype or member of what `:app` calls may name one.
 */
fun appPipelineWiring(
    appVersion: String,
    accentFolder: AccentFolder,
    resources: ResourceReader,
    artists: ArtistResults,
    genres: () -> Boolean = { true },
): PipelineWiring =
    PipelineWiring(
        NetClient(HostPolicies.netConfig(appVersion)),
        accentFolder,
        resources,
        rememberMusicBrainz = true,
        ownsNet = true,
        artists = artists,
        genres = genres,
    )
