package io.github.wkusnierczyk.sleevesight.cli

import io.github.wkusnierczyk.sleevesight.fixtures.BelowSoundtrack
import io.github.wkusnierczyk.sleevesight.fixtures.RegressionCase
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
              --model <id>        the Anthropic model (default: the app's default, claude-haiku-5-5)
              --no-web            leave web search off in a live run
              --no-insights       gather evidence only: no AI calls
              --record <list>     sources to record: musicbrainz, wikidata, wikipedia, anthropic
                                  (default: musicbrainz,wikidata,wikipedia)
              --keys <file>       the keys file (default: ~/.config/sleevesight/keys.env)

            Exit status: 0 when the gate's criteria pass, or nothing in the run applied to them;
            1 when one fails; 2 for a usage error, or a source request with no recording (a missing
            recorded AI reply is reported, not fatal) or whose answer could not be recorded; 3 when a
            criterion the run should have checked could not be, because a source was unreachable or
            failed, or an insight call failed.
            """.trimIndent()

        private val SOURCES = setOf("musicbrainz", "wikidata", "wikipedia", "anthropic")

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
            return Options(
                mode = mode,
                tracks = tracks(values["--tracks"] ?: "regression"),
                fixtures = File(values["--fixtures"] ?: "fixtures"),
                out = values["--out"]?.let(::File),
                cache = if (mode == Mode.RECORD) CacheChoice.None else cache,
                model = values["--model"],
                web = mode == Mode.LIVE && "--no-web" !in switches,
                insights = "--no-insights" !in switches,
                record = if (mode == Mode.RECORD) record else emptySet(),
                keysFile = values["--keys"]?.let(::File) ?: Keys.DEFAULT_FILE,
            )
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

        private val VALUED = setOf("--tracks", "--out", "--fixtures", "--cache", "--model", "--record", "--keys")

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
