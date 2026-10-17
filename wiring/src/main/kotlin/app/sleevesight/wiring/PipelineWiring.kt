package app.sleevesight.wiring

import app.sleevesight.ai.anthropic.AnthropicProvider
import app.sleevesight.core.ResourceReader
import app.sleevesight.core.Secret
import app.sleevesight.core.ai.AiProvider
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.MusicBrainzLabels
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.normalizer.AccentFolder
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.resolution.IdentityCheck
import app.sleevesight.core.resolution.MusicBrainzLookup
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.resolution.RecordingResolver
import app.sleevesight.core.resolution.RememberedMusicBrainz
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.WebEngine
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.net.NetClient
import app.sleevesight.sources.art.ArtFetcher
import app.sleevesight.sources.art.ArtResult
import app.sleevesight.sources.lastfm.LastFmAdapter
import app.sleevesight.sources.musicbrainz.MusicBrainzClient
import app.sleevesight.sources.musicbrainz.MusicBrainzSource
import app.sleevesight.sources.web.ExaWebSearch
import app.sleevesight.sources.wikidata.WikidataClient
import app.sleevesight.sources.wikipedia.WikipediaClient
import app.sleevesight.sources.wikipedia.WikipediaSource

/**
 * The pipeline's network side, built on one client (architecture, *Modules*): the source
 * adapters, the resolver, MusicBrainz's genre labels, the AI providers and the art fetch, each
 * handed out as a `:core` type or a plain function, so `:app` uses them without naming a `:net`
 * type. Nothing here sends a request of its own; the client is private, and no member takes or
 * returns a `:net` type, which `:app` could not compile against.
 *
 * Made by [appPipelineWiring] in the app, which creates the process's one client, and by
 * [pipelineWiringOn] in the CLI, on the client it creates (live, replaying or recording). The
 * [accentFolder] and the [resources] (the genre vocabulary) are the platform's.
 *
 * With [rememberMusicBrainz] (the app's), the resolver and Last.fm's check of an artist's name ask
 * MusicBrainz through one [RememberedMusicBrainz], which keeps its answers for a short time: what
 * preparing the next track looks up ahead (D13) is then not asked again when the track plays. The
 * CLI asks afresh for each track, so its reports time every request.
 */
class PipelineWiring internal constructor(
    private val net: NetClient,
    private val accentFolder: AccentFolder,
    resources: ResourceReader,
    rememberMusicBrainz: Boolean = false,
) {
    private val names = NameMatcher(accentFolder)

    // MusicBrainz's core data, for the resolver and Last.fm's check; the tags are asked apart.
    private val musicBrainz: MusicBrainzLookup =
        MusicBrainzClient(net).let { if (rememberMusicBrainz) RememberedMusicBrainz(it) else it }
    private val vocabulary by lazy { GenreVocabulary.load(resources) }
    private val artFetcher = ArtFetcher(net)

    /** Resolution over MusicBrainz (`RecordingResolver`), for `PipelineParts.resolver`. */
    val resolver: TrackResolver =
        RecordingResolver(musicBrainz, names).let { resolver ->
            TrackResolver { track, duration -> resolver.resolve(track, duration) }
        }

    /**
     * MusicBrainz's genre labels (D12, #84), for `PipelineParts.musicBrainzLabels`. One for the
     * wiring, since it keeps each artist's tags for the process.
     */
    val musicBrainzLabels: MusicBrainzLabels by lazy { MusicBrainzLabels(MusicBrainzClient(net), vocabulary) }

    /** The AI services with a client, by service: Anthropic, so far. */
    val aiProviders: Map<ProviderId, AiProvider> = mapOf(ProviderId.ANTHROPIC to AnthropicProvider(net))

    /**
     * The adapters for `PipelineParts.adapters`: MusicBrainz and Wikipedia (with Wikidata), which
     * need no key; Last.fm only with its key in [keys], since `Settings.givesLabels` does not know
     * about the key and relies on that; web search from the engines switched on in [settings] whose
     * key is in [keys] ([web]). A source switched off under *Where to look* still gets its adapter:
     * the pipeline reads the switch for each track.
     *
     * The keys and the web engines, unlike those switches, are read here, when the list is built: a
     * key saved or removed, or an engine switched, takes a new list, and so a new pipeline. Each call
     * builds new adapters, so whatever state one holds (`WikipediaSource`'s `IdentityCheck`
     * included) starts again.
     */
    fun adapters(
        keys: SourceKeys,
        settings: Settings,
    ): List<SourceAdapter> =
        listOfNotNull(
            musicBrainz(),
            wikipedia(),
            keys.lastFm?.let(::lastFm),
            web(keys.web.filterKeys { it in settings.webEngines }),
        )

    /** MusicBrainz's templated lines, from the resolution. */
    fun musicBrainz(): SourceAdapter = MusicBrainzSource()

    /** Wikipedia's lead sentences, with Wikidata's identity check. */
    fun wikipedia(): SourceAdapter =
        WikipediaSource(WikipediaClient(net), IdentityCheck(WikidataClient(net), names), names)

    /** Last.fm on the user's [key]. */
    fun lastFm(key: Secret): SourceAdapter = LastFmAdapter(net, musicBrainz, key, vocabulary, accentFolder)

    /**
     * Web search through the [engines] given, each with its key, or null for none. Exa is the only
     * engine (D11); a second comes with an adapter that searches each engine given.
     */
    fun web(engines: Map<WebEngine, Secret>): SourceAdapter? =
        engines[WebEngine.EXA]?.let { ExaWebSearch(net, it, accentFolder) }

    /**
     * The image at [uri], an `http` or `https` address, as served, or null for no art
     * (`ArtFetcher`): what `AppGraph` adapts into its `HttpArt`.
     */
    suspend fun art(uri: String): ByteArray? = (artFetcher.fetch(uri) as? ArtResult.Image)?.bytes
}

/** The user's keys for the keyed sources: Last.fm's, and each web search engine's (D11). */
class SourceKeys(
    val lastFm: Secret? = null,
    val web: Map<WebEngine, Secret> = emptyMap(),
)
