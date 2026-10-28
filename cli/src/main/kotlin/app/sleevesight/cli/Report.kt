package app.sleevesight.cli

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.genres.TrackGenres
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.model.outcomeKey
import app.sleevesight.core.pipeline.EventLine
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.synthesis.InsightPrompt
import app.sleevesight.core.synthesis.LEGACY_MODEL_NOTE
import app.sleevesight.core.synthesis.citesLegacyModelId
import app.sleevesight.store.CacheStats
import kotlin.time.Duration

/** Whether a source ran, and why not when it did not. */
data class SourceStatus(
    val source: SourceId,
    val on: Boolean,
    val note: String,
)

/**
 * What the run left besides its results: the events, the cache, the requests with no recording, and
 * the reads of the artist cache (#238) per part, null without a cache.
 */
class Diagnostics(
    val events: List<PipelineEvent>,
    val cache: CacheStats?,
    val misses: List<String>,
    val elapsed: Duration,
    val artistCache: List<ArtistCacheCount>? = null,
)

/**
 * Everything a report shows. [tracklist] names the tracklist run (`--tracklist`), null for the
 * fixture playlist; [allowModel] is the run's *Model's own knowledge* switch, which decides the
 * prompts [ArtistFacts] rebuilds; [passes] is how many times the tracks ran (`--repeat`), each pass
 * in [results] in turn. [artistFactsOncePerSession] is false for a run with every artist fact in
 * every prompt (`--artist-facts-every-track`, #407). [musicBrainzTrackLabels] counts the results
 * whose MusicBrainz recording tags would give the track labels (#439): a count, since the tags are
 * not listed.
 */
@Suppress("LongParameterList") // Everything one report shows, each its own part of it.
class RunSummary(
    val mode: Mode,
    val sources: List<SourceStatus>,
    val insights: String,
    val tracklist: String? = null,
    val allowModel: Boolean = false,
    val results: List<TrackResult>,
    val passes: Int = 1,
    val artistFactsOncePerSession: Boolean = true,
    val musicBrainzTrackLabels: Int = 0,
    val gate: List<Criterion>,
    val diagnostics: Diagnostics,
)

/**
 * The coverage report (`design/design.md`, *Diagnostics*, *Phase 0.5*), in Markdown: per source the
 * count of each outcome; per track the evidence lines and genre labels; the insights with their
 * check counts; the gate's criteria; the diagnostics events.
 *
 * What it may publish (`design/architecture.md`, *Licensing*): Wikipedia's sentences (CC BY-SA 4.0)
 * with their article links, MusicBrainz's and Wikidata's templated facts (CC0), Last.fm's lines with
 * their links. Never an Exa snippet: web results are shown as counts and URLs only (Exa's terms,
 * §4.2(a)), and an insight written from web lines is marked as one not to commit or post until
 * Exa's terms are confirmed. No MusicBrainz tag is listed: the app takes them as genre labels (D12,
 * #84), but this report lists each track's labels, and a list of them would be CC BY-NC-SA 3.0 to
 * commit or post (`architecture.md`, *Conditions*), so the CLI keeps them off as a label source,
 * and has no recordings of them either. The artists' are not fetched; the recording's come with its
 * lookup (#439), and are counted only ([RunSummary.musicBrainzTrackLabels]).
 */
class Report(
    private val run: RunSummary,
) {
    private val columns = run.sources.map { it.source }

    fun render(): String =
        buildString {
            header()
            coverage()
            gate()
            insights()
            artistFacts()
            appendLine("## Tracks")
            run.results.forEach { TrackSection(it, columns).render(this) }
            events()
        }

    private fun StringBuilder.header() {
        val d = run.diagnostics
        appendLine("# Sleevesight pipeline: coverage report")
        appendLine()
        val tracklist = run.tracklist?.let { " Tracklist: $it." }.orEmpty()
        val passes =
            if (run.passes > 1) {
                ", in ${run.passes} passes (--repeat: each a listening session of its own, every insight written " +
                    "afresh; the tables list every pass, and the gate pools them)"
            } else {
                ""
            }
        val facts =
            if (run.artistFactsOncePerSession) "" else " Artist facts: on every track (--artist-facts-every-track)."
        appendLine(
            "Mode: ${run.mode.name.lowercase()}. Tracks: ${run.results.size / run.passes}$passes.$tracklist$facts " +
                "Time: ${d.elapsed.inWholeSeconds} s.",
        )
        appendLine()
        appendLine("| Source | On | Note |")
        appendLine("|---|---|---|")
        for (s in run.sources) appendLine("| ${name(s.source)} | ${if (s.on) "yes" else "no"} | ${s.note} |")
        appendLine("| AI | | ${run.insights} |")
        appendLine()
        appendLine(NOTICE)
        d.cache?.let {
            appendLine()
            appendLine(
                "Cache after the run: retrieval ${it.retrieval.entries} entries (${it.retrieval.bytes} bytes), " +
                    "artist-level ${it.artistResults.entries} (${it.artistResults.bytes} bytes), " +
                    "synthesis ${it.synthesis.entries} (${it.synthesis.bytes} bytes).",
            )
        }
        d.artistCache?.let { counts ->
            appendLine()
            appendLine(artistCacheLine(counts))
        }
        if (d.misses.isNotEmpty()) {
            appendLine()
            appendLine("**Requests with no recording** (${d.misses.size}):")
            d.misses.forEach { appendLine("- `$it`") }
        }
        appendLine()
    }

    private fun StringBuilder.coverage() {
        appendLine("## Coverage per source")
        appendLine()
        appendLine("| Source | ${OUTCOMES.joinToString(" | ")} | unverified only | cached | cited lines |")
        appendLine("|---|${"---|".repeat(OUTCOMES.size + EXTRA_COLUMNS)}")
        for (source in columns) {
            val keys = run.results.map { it.state.sources[source]?.let(::stateKey) ?: OFF }
            val done = run.results.mapNotNull { it.state.sources[source] as? SourceState.Done }
            val unverifiedOnly = done.count { it.result.evidence.isEmpty() && it.result.unverified.isNotEmpty() }
            val counts = OUTCOMES.joinToString(" | ") { key -> keys.count { it == key }.toString() }
            val cited = done.sumOf { it.result.evidence.size }
            appendLine("| ${name(source)} | $counts | $unverifiedOnly | ${done.count { it.cached }} | $cited |")
        }
        val lines =
            run.results.map {
                it.state.evidence
                    ?.lines
                    ?.size ?: 0
            }
        val labelled = run.results.count { !it.state.genres.isEmpty }
        appendLine()
        appendLine(
            "Per track: ${lines.sum()} cited lines in all (${lines.minOrNull() ?: 0} to ${lines.maxOrNull() ?: 0}); " +
                "$labelled of ${run.results.size} tracks have genre labels.",
        )
        appendLine()
        levels()
    }

    // The tracks with labels of each level, by source (#439): whether the track's and album's pay off.
    private fun StringBuilder.levels() {
        val of = run.results.size

        fun count(
            level: GenreLevel,
            source: LabelSource,
        ): Int =
            run.results.count { result ->
                result.state.genres.rows.any { row ->
                    row.level == level && row.labels.any { label -> label.credits.any { it.source == source } }
                }
            }
        appendLine("| Genre labels | Last.fm | MusicBrainz |")
        appendLine("|---|---|---|")
        val track = count(GenreLevel.TRACK, LabelSource.LASTFM)
        appendLine("| Track | $track of $of | ${run.musicBrainzTrackLabels} of $of (counted, not shown) |")
        appendLine("| Artist | ${count(GenreLevel.ARTIST, LabelSource.LASTFM)} of $of | not asked |")
        appendLine(
            "| Album | ${count(GenreLevel.ALBUM, LabelSource.LASTFM)} of $of | none: it takes a request of its own |",
        )
        appendLine()
    }

    private fun StringBuilder.gate() {
        appendLine("## Gate (#46)")
        appendLine()
        appendLine("| Criterion | Verdict | Detail |")
        appendLine("|---|---|---|")
        for (c in run.gate) appendLine("| ${c.name} | ${c.verdict} | ${cell(c.detail)} |")
        appendLine("| The coverage numbers reviewed | owner | not a check |")
        appendLine()
    }

    // Each track run's calls and tokens, from its own events (#239): the one place the report counts them.
    private val calls: List<ArtistFacts.RunCalls> by lazy { ArtistFacts.RunCalls.perRun(run.diagnostics.events) }

    private fun callsOf(index: Int): ArtistFacts.RunCalls =
        calls.getOrNull(index) ?: ArtistFacts.RunCalls(emptyList(), null)

    private fun StringBuilder.insights() {
        val tried = run.results.withIndex().filter { it.value.state.insight !is InsightState.Idle }
        appendLine("## Insights")
        appendLine()
        if (tried.isEmpty()) {
            appendLine("None written (${run.insights}).")
            appendLine()
            return
        }
        appendLine("| Track | State | Sentences | ok | model | hedge | uncited | unknown | Tokens in / out |")
        appendLine("|---|---|---|---|---|---|---|---|---|")
        for ((index, result) in tried) {
            val insight = result.state.insight
            if (insight is InsightState.Written) {
                val c = insight.grounded.counts
                val tokens = tokens(callsOf(index), "cached")
                val state = if (insight.complete) "written" else "written, cut off"
                appendLine(
                    "| ${result.label} | $state | ${c.total} | ${c.ok} | ${c.model} | ${c.hedge} | ${c.uncited} | " +
                        "${c.unknown} | $tokens |",
                )
            } else {
                appendLine("| ${result.label} | ${insightLabel(insight)} | | | | | | | ${tokens(callsOf(index), "")} |")
            }
        }
        appendLine()
        appendLine("${billed(calls)} (prompt version ${InsightPrompt.VERSION}).")
        appendLine()
    }

    // #239: what consecutive tracks send and write about their artists (`ArtistFacts`).
    private fun StringBuilder.artistFacts() {
        val rows = ArtistFacts.measure(run.results, run.allowModel, calls)
        val t = ArtistFacts.totals(rows)
        appendLine("## Artist-level evidence (#239)")
        appendLine()
        appendLine(ARTIST_FACTS_NOTE)
        appendLine()
        appendLine(
            "| Track | Lines | Artist-level | Left out | Last prompt chars | Its artist-level chars | Resent | " +
                "Tokens in / out | Artist-only sentences | Artist lines cited | Restated from the last | " +
                "Restated from any |",
        )
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
        rows.forEachIndexed { index, r ->
            val written = r.insight is InsightState.Written
            val tokens = tokens(callsOf(index), if (written) "cached" else insightLabel(r.insight))
            val sentences = if (written) "${r.artistSentences} of ${r.sentences}" else ""
            appendLine(
                "| ${r.label} | ${r.lines} | ${r.artistLines} (${percent(r.artistLines, r.lines)}) | ${r.leftOut} | " +
                    "${r.promptChars ?: "not sent"} | ${r.artistChars} | ${r.resent} | $tokens | $sentences | " +
                    "${ids(r.cited, written)} | ${ids(r.fromLast, written)} | ${ids(r.restated, written)} |",
            )
        }
        appendLine()
        appendLine(
            "Over the run: ${t.artistLines} of ${t.lines} evidence lines artist-level " +
                "(${percent(t.artistLines, t.lines)}), ${t.leftOut} of them left out of the prompts; " +
                "${t.prompts} prompts sent, ${t.promptChars} characters, " +
                "${t.artistChars} of them artist-level lines (${percent(t.artistChars, t.promptChars)}). " +
                "Of ${t.written} insights, ${t.restatingLast} restate an artist fact of the last one before " +
                "and ${t.restating} one of any before; ${t.artistSentences} of ${t.sentences} sentences cite " +
                "artist-level lines only. ${billed(calls)}.",
        )
        appendLine()
    }

    private fun StringBuilder.events() {
        appendLine()
        appendLine("## Diagnostics log")
        appendLine()
        appendLine("```")
        run.diagnostics.events.forEach { appendLine(EventLine.of(it)) }
        appendLine("```")
    }

    companion object {
        /**
         * The artist cache's reads (#238): per part, how many found an entry (an artist-level lookup
         * not asked again) and how many did not.
         */
        fun artistCacheLine(counts: List<ArtistCacheCount>): String =
            if (counts.isEmpty()) {
                "Artist cache: not read."
            } else {
                "Artist cache: " +
                    counts.joinToString("; ") { "${it.part} ${it.hits} hits, ${it.misses} misses" } + "."
            }

        /** A source the CLI did not run (no key, or its terms) counts as switched off. */
        const val OFF = "source_off"
        const val WITHHELD = "web result, snippet not quoted (Exa's terms, §4.2(a))"
        private const val EXTRA_COLUMNS = 3

        val OUTCOMES: List<String> =
            listOf(
                "song_article",
                "artist_article",
                "original_song",
                "rejected",
                "none",
                OFF,
                "unreachable",
                "failed",
            )

        private const val NOTICE =
            "Web search results are shown as counts and URLs only: Exa's terms (§4.2(a)) forbid copying or " +
                "publishing what its service returns without its written permission, so no snippet is quoted " +
                "here, and an insight written from web lines is marked not to be committed or posted. " +
                "No MusicBrainz tag is listed (CC BY-NC-SA 3.0; D12): its artists' tags are not fetched, and the " +
                "recording's, which come with its lookup, are only counted; the genre labels listed are Last.fm's."

        fun name(source: SourceId): String =
            when (source) {
                SourceId.MUSICBRAINZ -> "MusicBrainz"
                SourceId.WIKIPEDIA -> "Wikipedia"
                SourceId.WIKIDATA -> "Wikidata"
                SourceId.LASTFM -> "Last.fm"
                SourceId.DISCOGS -> "Discogs"
                SourceId.WEB -> "Web search"
                SourceId.MODEL -> "Model"
                SourceId.PLAYER -> "Player"
            }

        fun stateKey(state: SourceState): String =
            when (state) {
                is SourceState.Done -> outcomeKey(state.result.outcome)
                is SourceState.Failed -> "failed"
                SourceState.Pending -> "pending"
            }

        fun insightLabel(insight: InsightState): String =
            when (insight) {
                is InsightState.Idle -> if (insight.canWrite) "not written (waits for a tap)" else "not written"
                is InsightState.Writing -> "writing"
                is InsightState.Written -> "written"
                InsightState.NoEvidence -> "no evidence"
                is InsightState.Failed -> "failed: ${insight.reason}"
                InsightState.Stopped -> "stopped"
            }

        /** A run's tokens, "in / out" over all its calls, with "(n calls)" for more than one; [none] without. */
        fun tokens(
            calls: ArtistFacts.RunCalls,
            none: String,
        ): String {
            val usage = calls.usage ?: return none
            val count = if (calls.made.size > 1) " (${calls.made.size} calls)" else ""
            return "${usage.inputTokens} / ${usage.outputTokens}$count"
        }

        /** What [runs] billed: every call's tokens, over the calls made, failed and stopped ones too. */
        fun billed(runs: List<ArtistFacts.RunCalls>): String {
            val usage = runs.mapNotNull { it.usage }
            return "Tokens billed: ${usage.sumOf { it.inputTokens }} in, ${usage.sumOf { it.outputTokens }} out, " +
                "over ${runs.sumOf { it.made.size }} calls made"
        }

        fun cell(text: String): String = text.replace("|", "\\|").replace("\n", " ")

        /** [part] of [whole] as a percentage, to one decimal; `-` for none of nothing. */
        fun percent(
            part: Int,
            whole: Int,
        ): String = if (whole == 0) "-" else Gate.percent(part.toDouble() / whole)

        private fun ids(
            ids: List<String>,
            written: Boolean,
        ): String =
            when {
                !written -> ""
                ids.isEmpty() -> "none"
                else -> ids.joinToString(", ")
            }

        private const val ARTIST_FACTS_NOTE =
            "Per track, in the run's order: its evidence lines and how many are artist-level (Last.fm's bio, " +
                "the Wikipedia artist article, Wikidata's artist facts); *left out*, the artist-level lines its " +
                "last prompt left out (#239, `PromptEvidence`: all for a track with enough of its own, else " +
                "those another insight of the listening session cited; none with --artist-facts-every-track, " +
                "#407); the size of the last prompt its run " +
                "sent, rebuilt as the pipeline built it (none for an insight from the cache or not written), " +
                "and of its artist-level lines; *resent*, those the prompt sent before it held too; the tokens " +
                "of every call the run made; the insight's sentences citing artist-level lines only; and the " +
                "artist-level lines it cites that the last insight before it, or any before it, cited too. The " +
                "summary counts every prompt sent. Lines are matched across tracks by source and text, since " +
                "ids are positions per track. Citations are what is counted, not prose: a fact restated from a " +
                "differently worded line, or uncited, is not."
    }
}

/** One track's section of the report. */
private class TrackSection(
    private val result: TrackResult,
    private val columns: List<SourceId>,
) {
    private val state = result.state
    private val done =
        columns.mapNotNull { source -> (state.sources[source] as? SourceState.Done)?.let { source to it } }

    fun render(out: StringBuilder) =
        with(out) {
            appendLine()
            val pass = result.pass?.let { " (pass $it)" }.orEmpty()
            appendLine("### ${result.n}. ${state.nowPlaying.title}, ${state.nowPlaying.artists}$pass")
            appendLine()
            state.nowPlaying.album?.let { appendLine("Release: $it.") }
            val artists = state.normalized.artists.joinToString(", ")
            appendLine("Normalized: \"${state.normalized.title}\" by $artists; key `${state.normalized.lookupKey}`.")
            appendLine()
            sources()
            rejections()
            evidence()
            appendLine()
            appendLine("Genre labels: ${labels(state.genres)}.")
            appendLine()
            insight()
        }

    // Each row of the genres: `Track: Folk; Artist (Gordon Lightfoot): Folk, Country`.
    private fun labels(genres: TrackGenres): String =
        genres.rows
            .joinToString("; ") { row ->
                val level =
                    row.level.name
                        .lowercase()
                        .replaceFirstChar(Char::uppercase)
                val name = row.artist?.let { " ($it)" }.orEmpty()
                "$level$name: ${row.labels.joinToString(", ") { it.genre.label }}"
            }.ifEmpty { "none" }

    private fun StringBuilder.sources() {
        appendLine("| Source | Outcome | Cited | Unverified | Latency | Cached |")
        appendLine("|---|---|---|---|---|---|")
        for (source in columns) {
            val name = Report.name(source)
            when (val s = state.sources[source]) {
                is SourceState.Done -> {
                    val r = s.result
                    val outcome = Report.cell(outcomeText(r.outcome))
                    val cached = if (s.cached) "yes" else ""
                    appendLine(
                        "| $name | $outcome | ${r.evidence.size} | ${r.unverified.size} | " +
                            "${r.latency.inWholeMilliseconds} ms | $cached |",
                    )
                }

                is SourceState.Failed -> {
                    appendLine("| $name | failed: ${s.reason} | | | | |")
                }

                SourceState.Pending -> {
                    appendLine("| $name | pending | | | | |")
                }

                null -> {
                    appendLine("| $name | ${Report.OFF} | | | | |")
                }
            }
        }
    }

    private fun StringBuilder.rejections() {
        val rejections = done.flatMap { (source, s) -> s.result.rejections.map { source to it } }
        if (rejections.isEmpty()) return
        appendLine()
        appendLine("Rejected:")
        rejections.forEach { (source, r) -> appendLine("- ${Report.name(source)}: ${r.subject}: ${r.reason}") }
    }

    private fun StringBuilder.evidence() {
        appendLine()
        appendLine("Evidence (cited):")
        val lines = state.evidence?.lines.orEmpty()
        if (lines.isEmpty()) appendLine("- none")
        lines.forEach { appendLine("- ${cited(it)}") }
        val unverified = done.flatMap { it.second.result.unverified }
        if (unverified.isNotEmpty()) {
            appendLine()
            appendLine("Unverified (shown, never cited):")
            unverified.forEach { appendLine("- ${shown(it)}") }
        }
    }

    private fun StringBuilder.insight() {
        val insight = state.insight
        if (insight !is InsightState.Written) {
            appendLine("Insight: ${Report.insightLabel(insight)}.")
            return
        }
        val c = insight.grounded.counts
        val cached = if (insight.cached) " (cached)" else ""
        appendLine(
            "Insight$cached: ok ${c.ok}, model ${c.model}, hedge ${c.hedge}, uncited ${c.uncited}, " +
                "unknown ${c.unknown}.",
        )
        if (state.evidence?.lines?.any { it.source == SourceId.WEB } == true) {
            appendLine("Written with web search lines: do not commit or post (Exa's terms, §4.2(a)).")
        }
        appendLine()
        for (paragraph in insight.grounded.paragraphs) {
            val text =
                paragraph.joinToString(" ") { sentence ->
                    val status = sentence.status.name
                    if (status in MARKED) "${sentence.text} _(${status.lowercase()})_" else sentence.text
                }
            appendLine("> $text")
        }
        if (citesLegacyModelId(insight.grounded.text)) {
            appendLine()
            appendLine("_${LEGACY_MODEL_NOTE}_")
        }
        insight.grounded.withdrawn?.let { withdrawn ->
            appendLine()
            appendLine("Taken out, the model's correction of its own reply (#597):")
            appendLine()
            withdrawn.lines().filter { it.isNotBlank() }.forEach { appendLine("> ${it.trim()}") }
        }
    }

    private companion object {
        val MARKED = setOf("UNCITED", "UNKNOWN", "MODEL", "HEDGE")

        fun outcomeText(outcome: Outcome): String =
            if (outcome is Outcome.Rejected) "rejected: ${outcome.reason}" else outcomeKey(outcome)

        fun cited(line: EvidenceLine): String {
            val text = if (line.source == SourceId.WEB) Report.WITHHELD else line.text
            return "[${line.id}] (${line.scope.key}) $text${line.url?.let { " <$it>" }.orEmpty()}"
        }

        fun shown(line: EvidenceDraft): String {
            val text = if (line.source == SourceId.WEB) Report.WITHHELD else line.text
            return "(${Report.name(line.source)}, ${line.scope.key}) $text${line.url?.let { " <$it>" }.orEmpty()}"
        }
    }
}
