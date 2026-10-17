package app.sleevesight.cli

import app.sleevesight.core.evidence.EvidenceDraft
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.model.outcomeKey
import app.sleevesight.core.pipeline.EventLine
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.SourceState
import app.sleevesight.core.synthesis.InsightPrompt
import app.sleevesight.store.CacheStats
import kotlin.time.Duration

/** Whether a source ran, and why not when it did not. */
data class SourceStatus(
    val source: SourceId,
    val on: Boolean,
    val note: String,
)

/** What the run left besides its results: the events, the cache, the requests with no recording. */
class Diagnostics(
    val events: List<PipelineEvent>,
    val cache: CacheStats?,
    val misses: List<String>,
    val elapsed: Duration,
)

/** Everything a report shows. */
class RunSummary(
    val mode: Mode,
    val sources: List<SourceStatus>,
    val insights: String,
    val results: List<TrackResult>,
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
 * Exa's terms are confirmed. MusicBrainz tags are not fetched, so none is listed: the app takes
 * them as genre labels (D12, #84), but this report lists each track's labels, and a list of them
 * would be CC BY-NC-SA 3.0 to commit or post (`architecture.md`, *Conditions*), so the CLI keeps
 * them off, and has no recordings of them either.
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
            appendLine("## Tracks")
            run.results.forEach { TrackSection(it, columns).render(this) }
            events()
        }

    private fun StringBuilder.header() {
        val d = run.diagnostics
        appendLine("# Sleevesight pipeline: coverage report")
        appendLine()
        appendLine(
            "Mode: ${run.mode.name.lowercase()}. Tracks: ${run.results.size}. Time: ${d.elapsed.inWholeSeconds} s.",
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
                    "synthesis ${it.synthesis.entries} (${it.synthesis.bytes} bytes).",
            )
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
        val labelled = run.results.count { it.state.genres.isNotEmpty() }
        appendLine()
        appendLine(
            "Per track: ${lines.sum()} cited lines in all (${lines.minOrNull() ?: 0} to ${lines.maxOrNull() ?: 0}); " +
                "$labelled of ${run.results.size} tracks have genre labels.",
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

    private fun StringBuilder.insights() {
        val tried = run.results.filter { it.state.insight !is InsightState.Idle }
        appendLine("## Insights")
        appendLine()
        if (tried.isEmpty()) {
            appendLine("None written (${run.insights}).")
            appendLine()
            return
        }
        appendLine("| Track | State | Sentences | ok | model | hedge | uncited | unknown | Tokens in / out |")
        appendLine("|---|---|---|---|---|---|---|---|---|")
        for (result in tried) {
            val insight = result.state.insight
            if (insight is InsightState.Written) {
                val c = insight.grounded.counts
                val tokens = insight.usage?.let { "${it.inputTokens} / ${it.outputTokens}" } ?: "cached"
                val state = if (insight.complete) "written" else "written, cut off"
                appendLine(
                    "| ${result.n} | $state | ${c.total} | ${c.ok} | ${c.model} | ${c.hedge} | ${c.uncited} | " +
                        "${c.unknown} | $tokens |",
                )
            } else {
                appendLine("| ${result.n} | ${insightLabel(insight)} | | | | | | | |")
            }
        }
        val usage = tried.mapNotNull { (it.state.insight as? InsightState.Written)?.usage }
        appendLine()
        appendLine(
            "Tokens billed: ${usage.sumOf { it.inputTokens }} in, ${usage.sumOf { it.outputTokens }} out, " +
                "over ${usage.size} calls (prompt version ${InsightPrompt.VERSION}).",
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
                "MusicBrainz tags are not fetched (CC BY-NC-SA 3.0; D12): genre labels come from Last.fm alone."

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
            }

        fun cell(text: String): String = text.replace("|", "\\|").replace("\n", " ")
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
            appendLine("### ${result.n}. ${state.nowPlaying.title}, ${state.nowPlaying.artists}")
            appendLine()
            state.nowPlaying.album?.let { appendLine("Release: $it.") }
            val artists = state.normalized.artists.joinToString(", ")
            appendLine("Normalized: \"${state.normalized.title}\" by $artists; key `${state.normalized.lookupKey}`.")
            appendLine()
            sources()
            rejections()
            evidence()
            appendLine()
            appendLine("Genre labels: ${state.genres.joinToString(", ") { it.genre.label }.ifEmpty { "none" }}.")
            appendLine()
            insight()
        }

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
