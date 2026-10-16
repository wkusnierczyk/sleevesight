package app.sleevesight.cli

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.sleevesight.ai.anthropic.AnthropicProvider
import app.sleevesight.core.Secret
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.NowPlaying
import app.sleevesight.core.model.ProviderId
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.normalizer.Normalizer
import app.sleevesight.core.pipeline.AiAccess
import app.sleevesight.core.pipeline.Pipeline
import app.sleevesight.core.pipeline.PipelineCache
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.PipelineParts
import app.sleevesight.core.pipeline.TrackResolver
import app.sleevesight.core.platform.JvmAccentFolder
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.platform.JvmResourceReader
import app.sleevesight.core.resolution.IdentityCheck
import app.sleevesight.core.resolution.NameMatcher
import app.sleevesight.core.resolution.RecordingResolver
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.settings.SourceSwitch
import app.sleevesight.core.sources.SourceAdapter
import app.sleevesight.fixtures.BelowSoundtrack
import app.sleevesight.net.NetClient
import app.sleevesight.net.NetConfig
import app.sleevesight.sources.lastfm.LastFmAdapter
import app.sleevesight.sources.musicbrainz.MusicBrainzClient
import app.sleevesight.sources.musicbrainz.MusicBrainzSource
import app.sleevesight.sources.web.ExaWebSearch
import app.sleevesight.sources.wikidata.WikidataClient
import app.sleevesight.sources.wikipedia.WikipediaClient
import app.sleevesight.sources.wikipedia.WikipediaSource
import app.sleevesight.store.Caches
import app.sleevesight.store.StorePipelineCache
import app.sleevesight.store.db.CacheDatabase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.File
import java.io.PrintStream
import java.util.Collections
import java.util.Properties
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The Phase 0.5 command-line tool (`design/design.md`, *Phase 0.5*): runs the pipeline over the
 * fixture playlist, one track after another, and prints the coverage report ([Report]), whose gate
 * criteria ([Gate]) decide the exit status. See [Options.USAGE].
 *
 * One [NetClient] per process (architecture, *Networking*), with each adapter's host policy
 * registered: MusicBrainz's is `:net`'s default, Exa's is added here.
 */
class Cli(
    private val out: PrintStream,
    private val err: PrintStream,
    private val environment: Map<String, String> = System.getenv(),
) {
    fun run(args: List<String>): Int {
        val options =
            try {
                Options.parse(args)
            } catch (e: IllegalArgumentException) {
                err.println(e.message)
                err.println(Options.USAGE)
                return EXIT_USAGE
            }
        return Session(options).use { it.run() }
    }

    private inner class Session(
        private val options: Options,
    ) : AutoCloseable {
        private val keys = if (options.mode == Mode.REPLAY) Keys.NONE else Keys.load(environment, options.keysFile)
        private val store = FixtureStore(File(options.fixtures, "http"))
        private val live: NetClient? = if (options.mode == Mode.REPLAY) null else NetClient(NET_CONFIG)
        private val replay = ReplayNetClient(store)
        private val recorder =
            if (options.mode == Mode.RECORD) live?.let { RecordingNetClient(it, store, options.record) } else null
        private val net: NetClient = recorder ?: live ?: replay
        private val cacheFile: File? =
            when (val choice = options.cache) {
                CacheChoice.None -> null
                CacheChoice.Temporary -> File.createTempFile("sleevesight-cli", ".db").also { it.delete() }
                is CacheChoice.At -> choice.file.also { it.absoluteFile.parentFile?.mkdirs() }
            }
        private val driver =
            cacheFile?.let { JdbcSqliteDriver("jdbc:sqlite:${it.path}", Properties(), CacheDatabase.Schema) }
        private val normalizer = Normalizer(JvmAccentFolder)
        private val names = NameMatcher(JvmAccentFolder)
        private val ai: AiAccess? = aiAccess()
        private val sources: List<Pair<SourceStatus, SourceAdapter?>> = sources()
        private val settings =
            Settings(
                aiProvider = ProviderId.ANTHROPIC,
                aiModel = options.model,
                enabledSources =
                    Settings.DEFAULT_SOURCES -
                        sources.filter { !it.first.on }.mapNotNull { SourceSwitch.of(it.first.source) }.toSet(),
                // MusicBrainz tags stay out of the report, which lists each track's labels (`Report`).
                labelSources = setOf(LabelSource.LASTFM),
            )
        private val caches = driver?.let { Caches(it, { settings }, normalizer) }

        fun run(): Int {
            caches?.purgeExpired()
            val started = TimeSource.Monotonic.markNow()
            val events = Collections.synchronizedList(mutableListOf<PipelineEvent>())
            val results = runAll(events)
            if (options.mode == Mode.RECORD) store.save()
            val misses = recorder?.misses ?: replay.misses
            val gate = Gate.evaluate(results, insightsAsked = ai != null)
            val summary =
                RunSummary(
                    mode = options.mode,
                    sources = sources.map { it.first },
                    insights = aiNote(),
                    results = results,
                    gate = gate,
                    diagnostics = Diagnostics(events.toList(), caches?.stats(), misses, started.elapsedNow()),
                )
            val report = Report(summary).render()
            out.print(report)
            options.out?.let { file ->
                file.absoluteFile.parentFile?.mkdirs()
                file.writeText(report)
            }
            // A missing reply of the model is reported, not fatal: a re-recorded source changes the prompts.
            val sourceMisses = misses.filter { FixtureStore.sourceOf(it.substringAfter(' ')) != ANTHROPIC }
            return when {
                sourceMisses.isNotEmpty() -> EXIT_USAGE
                gate.any { it.verdict == Verdict.FAIL } -> EXIT_GATE
                gate.any { it.incomplete } -> EXIT_UNCHECKED
                else -> 0
            }
        }

        private fun runAll(events: MutableList<PipelineEvent>): List<TrackResult> {
            val pipeline =
                Pipeline(
                    PipelineParts(
                        normalizer = normalizer,
                        adapters = sources.mapNotNull { it.second },
                        resolver = resolver(),
                        hasher = JvmHasher,
                        ai = ai,
                        cache = caches?.let { StorePipelineCache(it, Dispatchers.IO) } ?: PipelineCache.None,
                    ),
                ) { settings }
            return runBlocking {
                val log = launch(start = CoroutineStart.UNDISPATCHED) { pipeline.events.collect { events += it } }
                val results =
                    options.tracks.map { n ->
                        err.println("track $n")
                        TrackResult(n, pipeline.run(nowPlaying(n), write = ai != null))
                    }
                yield()
                log.cancel()
                results
            }
        }

        private fun resolver(): TrackResolver {
            val resolver = RecordingResolver(MusicBrainzClient(net), names)
            return TrackResolver { track, duration -> resolver.resolve(track, duration) }
        }

        private fun sources(): List<Pair<SourceStatus, SourceAdapter?>> {
            val identity = IdentityCheck(WikidataClient(net), names)
            return listOf(
                SourceStatus(SourceId.MUSICBRAINZ, true, "resolution and templated lines (CC0)") to MusicBrainzSource(),
                SourceStatus(SourceId.WIKIPEDIA, true, "with Wikidata (WD): lead sentences, CC BY-SA 4.0") to
                    WikipediaSource(WikipediaClient(net), identity, names),
                lastFm(),
                web(),
            )
        }

        private fun lastFm(): Pair<SourceStatus, SourceAdapter?> {
            val key = keys.lastFm
            return if (key != null && options.mode == Mode.LIVE) {
                val vocabulary = GenreVocabulary.load(JvmResourceReader)
                SourceStatus(SourceId.LASTFM, true, "") to
                    LastFmAdapter(net, MusicBrainzClient(net), key, vocabulary, JvmAccentFolder)
            } else {
                SourceStatus(SourceId.LASTFM, false, "off: no Last.fm key exists yet, so nothing is recorded either") to
                    null
            }
        }

        private fun web(): Pair<SourceStatus, SourceAdapter?> {
            val key = keys.exa
            val off =
                when {
                    options.mode == Mode.REPLAY -> "off: its fixtures are synthetic (Exa's terms), not facts"
                    options.mode == Mode.RECORD -> "off: Exa's results are not committed (§4.2(a))"
                    !options.web -> "off: --no-web"
                    key == null -> "off: no ${Keys.EXA}"
                    else -> null
                }
            return if (off != null || key == null) {
                SourceStatus(SourceId.WEB, false, off ?: "off") to null
            } else {
                SourceStatus(SourceId.WEB, true, "Exa, instant search") to ExaWebSearch(net, key, JvmAccentFolder)
            }
        }

        private fun aiAccess(): AiAccess? {
            val key =
                when {
                    !options.insights -> null
                    options.mode == Mode.REPLAY -> Secret("replay").takeIf { store.hasRecordings(ANTHROPIC) }
                    options.mode == Mode.RECORD -> keys.anthropic?.takeIf { ANTHROPIC in options.record }
                    else -> keys.anthropic
                }
            return key?.let { AiAccess(AnthropicProvider(net), it) }
        }

        private fun aiNote(): String {
            val model = options.model ?: Settings.DEFAULT_MODELS.getValue(ProviderId.ANTHROPIC)
            val effort = settings.aiEffort?.name?.lowercase() ?: "the model's default"
            val replayed = if (options.mode == Mode.REPLAY) ", replayed from the recorded replies" else ""
            return when {
                ai != null -> "Anthropic $model, effort $effort$replayed"
                !options.insights -> "off: --no-insights"
                options.mode == Mode.REPLAY -> "off: no recorded replies"
                else -> "off: no ${Keys.ANTHROPIC}"
            }
        }

        override fun close() {
            net.close()
            driver?.close()
            if (options.cache == CacheChoice.Temporary) cacheFile?.delete()
        }
    }

    companion object {
        const val APP_VERSION = "0.0"

        /** A gate criterion failed. */
        const val EXIT_GATE = 1

        /** A usage error, or a source's request with no recording (replay) or not recordable (record). */
        const val EXIT_USAGE = 2

        /** A criterion the run should have checked could not be: a source or an insight call failed. */
        const val EXIT_UNCHECKED = 3
        private const val ANTHROPIC = "anthropic"
        private const val SECONDS_PER_MINUTE = 60

        /** The one client's configuration: `:net`'s defaults (MusicBrainz) and Exa's policy. */
        val NET_CONFIG: NetConfig
            get() = NetConfig(APP_VERSION, NetConfig.DEFAULT_HOSTS + (ExaWebSearch.HOST to ExaWebSearch.HOST_POLICY))

        /** Playlist position [n] as the player would report it. */
        fun nowPlaying(n: Int): NowPlaying {
            val track = BelowSoundtrack.track(n)
            val (minutes, seconds) = track.duration.split(':').map { it.toInt() }
            return NowPlaying(
                title = track.title,
                artists = track.artist,
                album = BelowSoundtrack.albumFor(n).album,
                duration = (minutes * SECONDS_PER_MINUTE + seconds).seconds,
                player = Settings.SPOTIFY,
            )
        }
    }
}
