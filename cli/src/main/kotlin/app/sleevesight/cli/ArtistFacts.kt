package app.sleevesight.cli

import app.sleevesight.core.ai.Usage
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.pipeline.ArtistFact
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.platform.JvmHasher
import app.sleevesight.core.synthesis.InsightPrompt

/**
 * What consecutive tracks send and write about their artists (#239): per track of a run, in its
 * order, the share of the evidence that is artist-level ([Scope.ARTIST]: Last.fm's bio, the
 * Wikipedia artist article, Wikidata's artist facts), what that share costs in the prompt, and
 * which artist facts the insight restates from the ones before it.
 *
 * An artist-level line's id is its position among the track's lines (`W3` on one track may be
 * `W2` on the next), so lines are matched across tracks as [ArtistFact]s, by source and text, which
 * the artist-level cache (#238) keeps the same from one track to the next. An insight *restates* an
 * artist fact when it cites a line that an earlier insight of the run cited too; the measure sees
 * citations, not prose, so a fact restated from a differently worded line, or uncited, is not
 * counted.
 *
 * The prompt is the one the pipeline sends ([InsightPrompt.build]), rebuilt from the track's state
 * less the artist-level lines the call left out (`PipelineEvent.ModelCall.leftOut`), so its size is
 * known with no call; a track with nothing to narrate sends none.
 */
object ArtistFacts {
    /**
     * One track's numbers. [lines] and [artistLines] are the track's evidence; [leftOut] the
     * artist-level lines its prompt left out, as earlier insights cited them. [promptChars] is null
     * when no prompt is sent (nothing to narrate); [artistChars] counts the artist-level lines the
     * prompt holds as it writes them, a newline each. [resent] is the artist-level lines the
     * prompt holds that the run's track before this one sent too. [cited] are
     * this insight's citations of artist-level lines, by this track's ids; [restated] those an
     * insight before it in the run cited too, the last one written ([fromLast]) or any.
     * [artistSentences] counts the insight's sentences that cite artist-level lines only.
     */
    data class Row(
        val n: Int,
        val lines: Int,
        val artistLines: Int,
        val leftOut: Int,
        val promptChars: Int?,
        val artistChars: Int,
        val resent: Int,
        val insight: InsightState,
        val sentences: Int,
        val artistSentences: Int,
        val cited: List<String>,
        val fromLast: List<String>,
        val restated: List<String>,
    ) {
        val usage: Usage? get() = (insight as? InsightState.Written)?.usage
    }

    /** The run's totals over [rows]. */
    data class Totals(
        val lines: Int,
        val artistLines: Int,
        val leftOut: Int,
        val prompts: Int,
        val promptChars: Int,
        val artistChars: Int,
        val written: Int,
        val restatingLast: Int,
        val restating: Int,
        val sentences: Int,
        val artistSentences: Int,
        val calls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
    )

    /** The ids each track's last call left out, by lookup key, from the run's [events]. */
    fun leftOut(events: List<PipelineEvent>): Map<String, List<String>> =
        events.filterIsInstance<PipelineEvent.ModelCall>().associate { it.key to it.leftOut }

    /**
     * The numbers per track of [results], in their order; [allowModel] is the run's model-knowledge
     * switch, [leftOut] the artist-level lines each track's prompt left out ([ArtistFacts.leftOut]).
     */
    fun measure(
        results: List<TrackResult>,
        allowModel: Boolean,
        leftOut: Map<String, List<String>> = emptyMap(),
    ): List<Row> {
        var before: Set<ArtistFact> = emptySet()
        var lastCited: Set<ArtistFact> = emptySet()
        val everCited = mutableSetOf<ArtistFact>()
        return results.map { result ->
            val state = result.state
            val evidence = state.evidence
            val lines = evidence?.lines.orEmpty()
            val artist = lines.filter { it.scope == Scope.ARTIST }
            val out = leftOut[state.normalized.lookupKey].orEmpty().toSet()
            val sent =
                evidence
                    ?.takeUnless {
                        it.nothingToNarrate(allowModel, artistsKnown = state.normalized.artists.isNotEmpty())
                    }?.without(JvmHasher) { it.id in out }
            val sentArtist = sent?.lines.orEmpty().filter { it.scope == Scope.ARTIST }
            val prompt = sent?.let { InsightPrompt.build(state.nowPlaying, state.normalized, it, allowModel) }
            val sentences =
                (state.insight as? InsightState.Written)
                    ?.grounded
                    ?.paragraphs
                    ?.flatten()
                    .orEmpty()
            val artistIds = artist.associate { it.id to ArtistFact.of(it) }
            val cited = sentences.flatMap { it.cites }.filter { it in artistIds }.distinct()
            val citedFacts = cited.map { artistIds.getValue(it) }
            val row =
                Row(
                    n = result.n,
                    lines = lines.size,
                    artistLines = artist.size,
                    leftOut = artist.count { it.id in out },
                    promptChars = prompt?.length,
                    artistChars = sentArtist.sumOf { InsightPrompt.evidenceLine(it).length + 1 },
                    resent = sentArtist.count { ArtistFact.of(it) in before },
                    insight = state.insight,
                    sentences = sentences.size,
                    artistSentences = sentences.count { s -> s.cites.isNotEmpty() && s.cites.all { it in artistIds } },
                    cited = cited,
                    fromLast = cited.filter { artistIds.getValue(it) in lastCited },
                    restated = cited.filter { artistIds.getValue(it) in everCited },
                )
            before = sentArtist.map(ArtistFact::of).toSet()
            if (state.insight is InsightState.Written) {
                lastCited = citedFacts.toSet()
                everCited += citedFacts
            }
            row
        }
    }

    /** The totals of [rows]. */
    fun totals(rows: List<Row>): Totals {
        val written = rows.filter { it.insight is InsightState.Written }
        val usage = rows.mapNotNull { it.usage }
        return Totals(
            lines = rows.sumOf { it.lines },
            artistLines = rows.sumOf { it.artistLines },
            leftOut = rows.sumOf { it.leftOut },
            prompts = rows.count { it.promptChars != null },
            promptChars = rows.sumOf { it.promptChars ?: 0 },
            artistChars = rows.filter { it.promptChars != null }.sumOf { it.artistChars },
            written = written.size,
            restatingLast = written.count { it.fromLast.isNotEmpty() },
            restating = written.count { it.restated.isNotEmpty() },
            sentences = written.sumOf { it.sentences },
            artistSentences = written.sumOf { it.artistSentences },
            calls = usage.size,
            inputTokens = usage.sumOf { it.inputTokens },
            outputTokens = usage.sumOf { it.outputTokens },
        )
    }
}
