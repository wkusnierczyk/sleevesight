package app.sleevesight.cli

import app.sleevesight.core.ai.Usage
import app.sleevesight.core.evidence.EvidenceLine
import app.sleevesight.core.evidence.Scope
import app.sleevesight.core.pipeline.ArtistFact
import app.sleevesight.core.pipeline.InsightState
import app.sleevesight.core.pipeline.PipelineEvent
import app.sleevesight.core.pipeline.TrackState
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
 * The prompt is the one each call sent ([InsightPrompt.build]), rebuilt from the track's state less
 * the artist-level lines the call left out (`PipelineEvent.ModelCall.leftOut`), read from the track's
 * own events ([slices]); a track whose insight came from the cache, or was not written, sent none.
 */
object ArtistFacts {
    /**
     * One track's numbers. [lines] and [artistLines] are the track's evidence; [leftOut] the
     * artist-level lines its last call's prompt left out (#239, `PromptEvidence`). [calls] and
     * [usage] are its run's ([RunCalls]): two calls when `NO_EVIDENCE` to a prompt that left lines
     * out was asked again with them all. [promptChars] is the last call's prompt, null when the
     * track made no call (cached, not written, nothing to narrate), however its insight ended
     * (`NO_EVIDENCE` included); [artistChars] counts the artist-level lines that prompt holds as it
     * writes them, a newline each; [sentChars] and [sentArtistChars] are the same over every prompt
     * the run sent. [resent] is the artist-level lines the last prompt holds that the last prompt
     * sent before this track's held too. [cited] are
     * this insight's citations of artist-level lines, by this track's ids; [restated] those an
     * insight before it in the run cited too, the last one written ([fromLast]) or any.
     * [artistSentences] counts the insight's sentences that cite artist-level lines only.
     */
    data class Row(
        val n: Int,
        val lines: Int,
        val artistLines: Int,
        val leftOut: Int,
        val calls: Int,
        val usage: Usage?,
        val promptChars: Int?,
        val artistChars: Int,
        val sentChars: Int,
        val sentArtistChars: Int,
        val resent: Int,
        val insight: InsightState,
        val sentences: Int,
        val artistSentences: Int,
        val cited: List<String>,
        val fromLast: List<String>,
        val restated: List<String>,
    )

    /**
     * The run's totals over [rows]: [prompts], [promptChars] and [artistChars] over every prompt
     * sent, [calls] every call made (failed and stopped ones too), the tokens of every call whose
     * reply ended.
     */
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

    /**
     * A track run's calls, from its slice of the run's events ([slices]): the one place the report's
     * call counts and tokens come from (#239). [made] are every call the run made (`ModelCall`;
     * failed and stopped ones too), [usage] the tokens of those whose reply ended (`ModelDone`),
     * summed, null with none.
     */
    class RunCalls(
        val made: List<PipelineEvent.ModelCall>,
        val usage: Usage?,
    ) {
        companion object {
            /** The calls in [slice]. */
            fun of(slice: List<PipelineEvent>): RunCalls {
                val done = slice.filterIsInstance<PipelineEvent.ModelDone>().map { it.usage }
                val usage =
                    done.takeIf { it.isNotEmpty() }?.let { u ->
                        Usage(u.sumOf { it.inputTokens }, u.sumOf { it.outputTokens })
                    }
                return RunCalls(slice.filterIsInstance<PipelineEvent.ModelCall>(), usage)
            }

            /** Each track run's calls in [events], in run order. */
            fun perRun(events: List<PipelineEvent>): List<RunCalls> = slices(events).map(::of)
        }
    }

    /**
     * The run's [events], one slice per track run, in run order: each run begins with its
     * `Received`, so a track's calls are its own, however its insight ended and however often the
     * same track comes again.
     */
    fun slices(events: List<PipelineEvent>): List<List<PipelineEvent>> {
        val out = mutableListOf<MutableList<PipelineEvent>>()
        for (event in events) {
            if (event is PipelineEvent.Received || out.isEmpty()) out += mutableListOf<PipelineEvent>()
            out.last() += event
        }
        return out
    }

    /**
     * The numbers per track of [results], in their order; [allowModel] is the run's model-knowledge
     * switch, [runs] each run's calls ([RunCalls.perRun]), whose prompts are the ones measured: a
     * track that made none sent no prompt.
     */
    fun measure(
        results: List<TrackResult>,
        allowModel: Boolean,
        runs: List<RunCalls> = emptyList(),
    ): List<Row> {
        var before: Set<ArtistFact> = emptySet()
        var lastCited: Set<ArtistFact> = emptySet()
        val everCited = mutableSetOf<ArtistFact>()
        return results.mapIndexed { index, result ->
            val state = result.state
            val evidence = state.evidence
            val lines = evidence?.lines.orEmpty()
            val artist = lines.filter { it.scope == Scope.ARTIST }
            val calls = runs.getOrNull(index) ?: RunCalls(emptyList(), null)
            val prompts = prompts(state, calls, allowModel)
            val last = prompts.lastOrNull()
            val out =
                calls.made
                    .lastOrNull()
                    ?.leftOut
                    .orEmpty()
                    .toSet()
            val sentArtist = last?.third.orEmpty()
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
                    calls = calls.made.size,
                    usage = calls.usage,
                    promptChars = last?.first,
                    artistChars = last?.second ?: 0,
                    sentChars = prompts.sumOf { it.first },
                    sentArtistChars = prompts.sumOf { it.second },
                    resent = sentArtist.count { ArtistFact.of(it) in before },
                    insight = state.insight,
                    sentences = sentences.size,
                    artistSentences = sentences.count { s -> s.cites.isNotEmpty() && s.cites.all { it in artistIds } },
                    cited = cited,
                    fromLast = cited.filter { artistIds.getValue(it) in lastCited },
                    restated = cited.filter { artistIds.getValue(it) in everCited },
                )
            if (last != null) before = sentArtist.map(ArtistFact::of).toSet()
            if (state.insight is InsightState.Written) {
                lastCited = citedFacts.toSet()
                everCited += citedFacts
            }
            row
        }
    }

    // Each call's prompt, rebuilt: the evidence less the lines it left out; its length, its
    // artist-level lines' length, and those lines.
    private fun prompts(
        state: TrackState,
        calls: RunCalls,
        allowModel: Boolean,
    ): List<Triple<Int, Int, List<EvidenceLine>>> =
        calls.made.mapNotNull { call ->
            state.evidence?.without(JvmHasher) { it.id in call.leftOut }?.let { sent ->
                val artistSent = sent.lines.filter { it.scope == Scope.ARTIST }
                Triple(
                    InsightPrompt.build(state.nowPlaying, state.normalized, sent, allowModel).length,
                    artistSent.sumOf { InsightPrompt.evidenceLine(it).length + 1 },
                    artistSent,
                )
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
            prompts = rows.sumOf { it.calls },
            promptChars = rows.sumOf { it.sentChars },
            artistChars = rows.sumOf { it.sentArtistChars },
            written = written.size,
            restatingLast = written.count { it.fromLast.isNotEmpty() },
            restating = written.count { it.restated.isNotEmpty() },
            sentences = written.sumOf { it.sentences },
            artistSentences = written.sumOf { it.artistSentences },
            calls = rows.sumOf { it.calls },
            inputTokens = usage.sumOf { it.inputTokens },
            outputTokens = usage.sumOf { it.outputTokens },
        )
    }
}
