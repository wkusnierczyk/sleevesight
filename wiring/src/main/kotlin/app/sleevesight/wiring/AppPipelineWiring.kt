package app.sleevesight.wiring

import app.sleevesight.core.ResourceReader
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.pipeline.ClearEpoch
import app.sleevesight.core.resolution.MusicBrainzLookup
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.ArtistResults
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetLog
import kotlin.time.Duration

/**
 * The app's wiring, on the process's one client, which this creates from [HostPolicies.netConfig]
 * with the app's [appVersion] (its `versionName`). Called once per process, by `AppGraph` (#51):
 * the rate limiters belong to the client, so a second wiring would double every limit. The client
 * lives as long as the process; [PipelineWiring.close] closes it, for tests. MusicBrainz's answers
 * are kept for a short time ([PipelineWiring], `RememberedMusicBrainz`), for preparing the next
 * track (D13). What sources give for an artist is kept in [artists], the app's artist cache (#238);
 * what an album's or an artist's web search found, in [trackOnly], the same cache kept whatever *Keep
 * what sources said about each artist* says (#885, #912, [PipelineWiring.TRACK_ONLY_PARTS]).
 * [genres] is whether genre information is asked for (*Show genres*, #444), as last read.
 * [epoch] is *Clear history*'s boundary (#547), [musicBrainzThrough] a stand-in for the tests, and
 * [aiThrough] what wraps each AI service's client, the debug builds' log of requests ([PipelineWiring]).
 * [keepsArtists] is *Keep what sources said about each artist*, as last read, which says whether what
 * the search in the track's language found about an artist or an album is kept in [artists] or in
 * memory (#939, [PipelineWiring.languageLines]). [netLog] takes the client's log, a line for each
 * attempt of each request, redacted (`:net`'s `Redaction`): the debug builds' log of requests; by
 * default nowhere. [musicBrainzWait] is *Wait when busy* (#1120), as last read: how long a MusicBrainz
 * request it refuses as busy is asked again ([HostPolicies.netConfig]).
 *
 * Its parameters and result are `:core` types and plain values, so `:app` calls it without `:net`
 * on its compile classpath. Kept apart from [pipelineWiringOn], which takes a `:net` type: no
 * overload, supertype or member of what `:app` calls may name one.
 */
@Suppress("LongParameterList") // The platform's parts, then how the app's wiring differs, each defaulted.
fun appPipelineWiring(
    appVersion: String,
    accentFolder: AccentFolder,
    resources: ResourceReader,
    artists: ArtistResults,
    trackOnly: ArtistResults = artists,
    genres: () -> Boolean = { true },
    epoch: ClearEpoch = ClearEpoch(),
    musicBrainzThrough: (MusicBrainzLookup) -> MusicBrainzLookup = { it },
    aiThrough: (AiProvider) -> AiProvider = { it },
    keepsArtists: () -> Boolean = { true },
    netLog: (String) -> Unit = {},
    musicBrainzWait: () -> Duration = { Settings.DEFAULT_MUSICBRAINZ_WAIT },
): PipelineWiring =
    PipelineWiring(
        NetClient(HostPolicies.netConfig(appVersion, musicBrainzWait), NetLog(netLog)),
        accentFolder,
        resources,
        rememberMusicBrainz = true,
        ownsNet = true,
        artists = artists,
        trackOnly = trackOnly,
        genres = genres,
        epoch = epoch,
        musicBrainzThrough = musicBrainzThrough,
        aiThrough = aiThrough,
        keepsArtists = keepsArtists,
    )
