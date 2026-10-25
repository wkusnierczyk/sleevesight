package app.sleevesight.core.pipeline

import app.sleevesight.core.model.Outcome
import app.sleevesight.core.model.outcomeKey
import kotlin.time.Duration

/**
 * A diagnostics event as one line of the log, as the simulator's log words it (`design/design.md`,
 * *Diagnostics*): the CLI's report and the app's diagnostics screen show the same lines.
 */
object EventLine {
    // One branch per event type, each a line of text: splitting it would only scatter the format.
    @Suppress("CyclomaticComplexMethod")
    fun of(e: PipelineEvent): String =
        when (e) {
            is PipelineEvent.Received -> "received   \"${e.nowPlaying.title}\" by ${e.nowPlaying.artists}"
            is PipelineEvent.Normalized -> "normalized ${e.key}${notes(e.track.notes)}"
            is PipelineEvent.Resolved -> "resolved   ${e.key}: ${outcome(e.outcome, e.failure)} in ${ms(e.latency)}"
            is PipelineEvent.Prepared -> prepared(e)
            is PipelineEvent.SourceLanded -> landed(e)
            is PipelineEvent.SourceFailed -> "failed     ${e.key}: ${e.source.tag} ${e.reason}"
            is PipelineEvent.NotCached -> "not cached ${e.key}: ${e.source.tag}, ${e.reason}"
            is PipelineEvent.CacheFailed -> "cache      ${e.key}: ${e.operation} failed, ${e.reason}"
            is PipelineEvent.EvidenceAssembled -> "evidence   ${e.key}: ${e.lines} lines from ${e.sources} sources"
            is PipelineEvent.InsightCached -> "insight    ${e.key}: cache hit"
            is PipelineEvent.InsightWaits -> "insight    ${e.key}: ${e.reason}"
            is PipelineEvent.ModelCall ->
                "model      ${e.key}: ${e.provider.name.lowercase()} ${e.model}" +
                    if (e.leftOut.isEmpty()) "" else ", lines left out: ${e.leftOut.joinToString()}"
            is PipelineEvent.ModelDone -> modelDone(e)
            is PipelineEvent.ModelFailed -> "model      ${e.key}: failed, ${e.kind.name.lowercase()}"
            is PipelineEvent.ModelStopped -> "model      ${e.key}: stopped"
            is PipelineEvent.Grounded -> grounded(e)
            is PipelineEvent.NoEvidence -> "insight    ${e.key}: no evidence"
            is PipelineEvent.Superseded -> "superseded ${e.key}"
            is PipelineEvent.AskedAgain -> "again      ${e.key}: ${e.sources.joinToString(", ") { it.tag }} asked again"
        }

    private fun prepared(e: PipelineEvent.Prepared): String =
        "prepared   ${e.key}: ${outcome(e.outcome, e.failure)} in ${ms(e.latency)}" +
            if (e.again) ", second try" else ""

    // The outcome, and why the source could not be asked when it names that (#191).
    private fun outcome(
        outcome: Outcome,
        failure: String?,
    ): String = outcomeKey(outcome) + (failure?.let { " ($it)" } ?: "")

    private fun landed(e: PipelineEvent.SourceLanded): String =
        "source     ${e.key}: ${e.source.tag} ${outcomeKey(e.outcome)}, ${e.evidence} cited, " +
            "${e.unverified} unverified, ${e.rejections} rejected, ${ms(e.latency)}" +
            if (e.cached) ", cache hit" else ""

    private fun modelDone(e: PipelineEvent.ModelDone): String =
        "model      ${e.key}: ${e.stopReason.name.lowercase()}, ${e.usage.inputTokens} in, " +
            "${e.usage.outputTokens} out, ${ms(e.latency)}"

    private fun grounded(e: PipelineEvent.Grounded): String =
        "grounded   ${e.key}: ok ${e.counts.ok}, model ${e.counts.model}, hedge ${e.counts.hedge}, " +
            "uncited ${e.counts.uncited}, unknown ${e.counts.unknown}"

    private fun notes(notes: List<String>): String = if (notes.isEmpty()) "" else "  [${notes.joinToString("; ")}]"

    private fun ms(duration: Duration): String = "${duration.inWholeMilliseconds} ms"
}
