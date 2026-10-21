package app.sleevesight.cli

import app.sleevesight.core.model.ProviderId
import app.sleevesight.fixtures.BelowSoundtrack
import app.sleevesight.fixtures.RegressionCase
import java.io.File

/** How the CLI reaches the sources. */
enum class Mode {
    /** From the recorded responses in `fixtures/http/`: no network, no keys. What CI runs. */
    REPLAY,

    /** Against the live services, with the developer's keys. */
    LIVE,

    /** Live for the sources named, replayed for the others, recording what the live ones answer. */
    RECORD,
}

/** Where the caches live for a run. */
sealed interface CacheChoice {
    /** No cache: every lookup and every card is made afresh. */
    data object None : CacheChoice

    /** A fresh database for this run only, deleted after it: duplicates hit it, nothing carries over. */
    data object Temporary : CacheChoice

    /** A database file kept across runs. */
    data class At(
        val file: File,
    ) : CacheChoice
}

/** The command line, parsed. */
data class Options(
    val mode: Mode,
    val tracks: List<Int>,
    val fixtures: File,
    val out: File?,
    val cache: CacheChoice,
    val provider: ProviderId,
    val model: String?,
    val web: Boolean,
    val insights: Boolean,
    val record: Set<String>,
    val keysFile: File,
) {
    companion object {
        val USAGE: String =
            """
            Usage: sleevesight-cli <replay|live|record> [options]
                   sleevesight-cli keys-qr [options]   (a QR code of the keys for the app: below)

              replay   Run the pipeline over the recorded responses in fixtures/http/: no network, no keys.
                       Fails on any source request with no recording.
              live     Run it against the live services, with the keys in the environment or the keys file.
              record   Like live for the sources given by --record, replayed for the others; writes what
                       the live ones answer into fixtures/http/. Web search is off: Exa's results are not
                       committed. The cache is off, so every request is made.

            Options:
              --tracks <spec>     regression (default: the design's regression cases), all, or positions
                                  such as 1,12,20-24
              --out <file>        also write the report to this file
              --fixtures <dir>    the fixtures directory (default: fixtures)
              --cache <file|none> the cache database (default: a fresh temporary one for replay,
                                  .dev/cli/cache.db for live; none for record)
              --provider <name>   the AI service: anthropic, openai or google (default: the one
                                  --record names, else anthropic); openai only for live, since
                                  only Anthropic's and Google's replies are recorded and replayed
              --model <id>        the provider's model (default: the app's default for it)
              --no-web            leave web search off in a live run
              --no-insights       gather evidence only: no AI calls
              --record <list>     sources to record: musicbrainz, wikidata, wikipedia, and the AI
                                  services anthropic, google (default: musicbrainz,wikidata,wikipedia)
              --keys <file>       the keys file (default: ~/.config/sleevesight/keys.env)

            Exit status: 0 when the gate's criteria pass, or nothing in the run applied to them;
            1 when one fails; 2 for a usage error, or a source request with no recording (a missing
            recorded AI reply is reported, not fatal) or whose answer could not be recorded; 3 when a
            criterion the run should have checked could not be, because a source was unreachable or
            failed, or an insight call failed.
            """.trimIndent() + "\n\n" + KeysQr.USAGE

        /**
         * The AI services the CLI can run, by the name `--provider` and `--record` take, which is
         * also the directory under `fixtures/http/` of those whose replies are recorded.
         */
        val PROVIDERS: Map<String, ProviderId> =
            mapOf("anthropic" to ProviderId.ANTHROPIC, "openai" to ProviderId.OPENAI, "google" to ProviderId.GOOGLE)

        /** The AI services whose replies are recorded and replayed: those with a host in [FixtureStore.SOURCES]. */
        val RECORDED_AI: Set<String> = PROVIDERS.keys.filter { it in FixtureStore.SOURCES.values }.toSet()

        private val SOURCES = setOf("musicbrainz", "wikidata", "wikipedia") + RECORDED_AI

        /** The name of [provider] in `--provider`, `--record` and `fixtures/http/`. */
        fun nameOf(provider: ProviderId): String = PROVIDERS.entries.first { it.value == provider }.key

        /** Parses [args]; throws [IllegalArgumentException] with the reason for a bad command line. */
        fun parse(args: List<String>): Options {
            require(args.isNotEmpty()) { "No mode given" }
            val mode =
                Mode.entries.firstOrNull { it.name.equals(args.first(), ignoreCase = true) }
                    ?: throw IllegalArgumentException("Unknown mode ${args.first()}")
            val (values, switches) = read(args.drop(1))
            val record =
                values["--record"]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?: setOf("musicbrainz", "wikidata", "wikipedia")
            require(record.all { it in SOURCES }) { "--record takes ${SOURCES.joinToString()}" }
            val cache = cacheChoice(mode, values["--cache"])
            val provider = provider(values["--provider"], if (mode == Mode.RECORD) record else emptySet())
            require(mode == Mode.LIVE || nameOf(provider) in RECORDED_AI) {
                "--provider ${nameOf(provider)} runs only live: its replies are not recorded or replayed"
            }
            return Options(
                mode = mode,
                tracks = tracks(values["--tracks"] ?: "regression"),
                fixtures = File(values["--fixtures"] ?: "fixtures"),
                out = values["--out"]?.let(::File),
                cache = if (mode == Mode.RECORD) CacheChoice.None else cache,
                provider = provider,
                model = values["--model"],
                web = mode == Mode.LIVE && "--no-web" !in switches,
                insights = "--no-insights" !in switches,
                record = if (mode == Mode.RECORD) record else emptySet(),
                keysFile = values["--keys"]?.let(::File) ?: Keys.DEFAULT_FILE,
            )
        }

        /**
         * The AI service: [given] (`--provider`), else the one [record] names, else Anthropic. A
         * record run records the replies of the service it runs, so it names at most one, and
         * `--provider` must agree with it.
         */
        private fun provider(
            given: String?,
            record: Set<String>,
        ): ProviderId {
            val chosen =
                given?.let { name ->
                    requireNotNull(PROVIDERS[name]) { "--provider takes ${PROVIDERS.keys.joinToString()}" }
                }
            val recorded = record.filter { it in RECORDED_AI }
            require(recorded.size <= 1) { "--record takes one AI service at a time, was ${recorded.joinToString()}" }
            val service = recorded.singleOrNull()
            require(given == null || service == null || given == service) {
                "--record $service records $service's replies: give --provider $service, or leave --provider out"
            }
            return chosen ?: service?.let(PROVIDERS::getValue) ?: ProviderId.ANTHROPIC
        }

        private fun cacheChoice(
            mode: Mode,
            given: String?,
        ): CacheChoice =
            when (given) {
                null -> if (mode == Mode.LIVE) CacheChoice.At(File(".dev/cli/cache.db")) else CacheChoice.Temporary
                "none" -> CacheChoice.None
                else -> CacheChoice.At(File(given))
            }

        // The options with a value, and the switches.
        private fun read(args: List<String>): Pair<Map<String, String>, Set<String>> {
            val values = mutableMapOf<String, String>()
            val switches = mutableSetOf<String>()
            val rest = args.iterator()
            while (rest.hasNext()) {
                val arg = rest.next()
                when (arg) {
                    in SWITCHES -> {
                        switches += arg
                    }

                    in VALUED -> {
                        require(rest.hasNext()) { "$arg needs a value" }
                        values[arg] = rest.next()
                    }

                    else -> {
                        throw IllegalArgumentException("Unknown option $arg")
                    }
                }
            }
            return values to switches
        }

        private val SWITCHES = setOf("--no-web", "--no-insights")

        private val VALUED =
            setOf("--tracks", "--out", "--fixtures", "--cache", "--provider", "--model", "--record", "--keys")

        /** The playlist positions [spec] names. */
        fun tracks(spec: String): List<Int> {
            val known = BelowSoundtrack.tracks.map { it.n }.toSet()
            val positions =
                when (spec) {
                    "regression" -> RegressionCase.entries.flatMap { it.tracks }
                    "all" -> known.toList()
                    else ->
                        spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { part ->
                            if ('-' in part) {
                                val (from, to) = part.split('-').map { it.trim().toInt() }
                                (from..to).toList()
                            } else {
                                listOf(part.toInt())
                            }
                        }
                }
            val unknown = positions.filter { it !in known }
            require(unknown.isEmpty()) { "No such playlist positions: $unknown" }
            return positions.distinct().sorted()
        }
    }
}
