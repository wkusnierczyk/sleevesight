package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightPrompt
import app.sleevesight.core.synthesis.InsightReply
import kotlin.time.Duration

/**
 * The home insight for one track, once its [evidence] is in: from the synthesis cache when it has
 * this exact card (R2), else written by the model when asked, and always checked
 * ([GroundingCheck]) before it is shown. No evidence means no call: there is nothing to narrate,
 * and the call would be paid for a `NO_EVIDENCE`.
 */
internal class InsightWriter(
    private val parts: PipelineParts,
    private val time: PipelineTime,
    input: RunInput,
    private val evidence: EvidenceSet,
    private val guard: CacheGuard,
    private val sink: RunSink,
) {
    private val settings = input.settings
    private val nowPlaying = input.nowPlaying
    private val track = input.track
    private val key = track.lookupKey
    private val allowModel = settings.isEnabled(SourceId.MODEL)

    /**
     * Writes at once when [control] decides so; else, when it waits for a tap, once the user taps
     * Write; else leaves it offered. A model call reports its end to [control], however it ends.
     */
    suspend fun write(control: RunControl) {
        val ai = parts.ai
        val model = settings.aiModel ?: ai?.let { Settings.DEFAULT_MODELS[it.provider.id] }
        when {
            !settings.writeInsights || ai == null || model == null -> {
                control.decidedWithoutCall()
                show(InsightState.Idle(canWrite = false))
            }

            evidence.isEmpty() -> {
                control.decidedWithoutCall()
                emit(PipelineEvent.NoEvidence(key))
                show(InsightState.NoEvidence)
            }

            else -> {
                fromCacheOrModel(ai, model, control)
            }
        }
    }

    private suspend fun fromCacheOrModel(
        ai: AiAccess,
        model: String,
        control: RunControl,
    ) {
        val wanted = cardKey(ai, model)
        val kept = guard.attempt("read insight") { card(wanted) }
        when {
            kept != null -> {
                emit(PipelineEvent.InsightCached(key))
                val shown = checked(kept, cached = true, usage = null, complete = true)
                control.cardShown(shown as? InsightState.Written, evidence)
                show(shown)
            }

            control.decide(settings) -> {
                call(ai, model, wanted, control)
            }

            else -> {
                show(InsightState.Idle(canWrite = true))
                emit(PipelineEvent.InsightWaits(key, WAITS_FOR_TAP))
                if (control.waitsForTap) {
                    control.awaitTap()
                    call(ai, model, wanted, control)
                }
            }
        }
    }

    private suspend fun call(
        ai: AiAccess,
        model: String,
        card: CardKey,
        control: RunControl,
    ) {
        try {
            stream(ai, model, card, control)
        } finally {
            control.ended(RunStep.MODEL_CALL)
        }
    }

    private suspend fun stream(
        ai: AiAccess,
        model: String,
        card: CardKey,
        control: RunControl,
    ) {
        emit(PipelineEvent.ModelCall(key, ai.provider.id, model))
        show(InsightState.Writing(""))
        val started = time.timeSource.markNow()
        val prompt = InsightPrompt.build(nowPlaying, track, evidence, allowModel)
        val text = StringBuilder()
        var end: CompletionEvent? = null
        ai.provider.stream(CompletionRequest(model, prompt, effort = settings.aiEffort), ai.key).collect { event ->
            when (event) {
                is CompletionEvent.Delta -> {
                    text.append(event.text)
                    show(InsightState.Writing(text.toString()))
                }

                is CompletionEvent.Done, is CompletionEvent.Error -> {
                    end = event
                }
            }
        }
        when (val last = end) {
            is CompletionEvent.Done -> finish(last, text.toString(), card, started.elapsedNow(), control)
            is CompletionEvent.Error -> {
                emit(PipelineEvent.ModelFailed(key, last.kind))
                show(InsightState.Failed(last.kind.name.lowercase()))
            }

            else -> show(InsightState.Failed(INCOMPLETE))
        }
    }

    private suspend fun finish(
        done: CompletionEvent.Done,
        text: String,
        card: CardKey,
        latency: Duration,
        control: RunControl,
    ) {
        emit(PipelineEvent.ModelDone(key, done.usage, done.stopReason, latency))
        if (done.stopReason == StopReason.REFUSAL) return show(InsightState.Failed(DECLINED))
        val complete = done.stopReason == StopReason.END
        if (complete) guard.attempt("write insight") { keep(card, text) }
        val written = checked(text, cached = false, usage = done.usage, complete = complete)
        if (written is InsightState.Written) control.cardWritten(written, evidence)
        show(written)
    }

    private fun checked(
        text: String,
        cached: Boolean,
        usage: Usage?,
        complete: Boolean,
    ): InsightState =
        when (val reply = GroundingCheck.check(text, evidence, allowModel)) {
            InsightReply.NoEvidence -> {
                emit(PipelineEvent.NoEvidence(key))
                InsightState.NoEvidence
            }

            is InsightReply.Written -> {
                emit(PipelineEvent.Grounded(key, reply.grounded.counts))
                InsightState.Written(reply.grounded, cached, usage, complete)
            }
        }

    private fun cardKey(
        ai: AiAccess,
        model: String,
    ): CardKey =
        CardKey(
            kind = CardKind.INSIGHT,
            track = track,
            duration = nowPlaying.duration,
            evidenceFingerprint = evidence.fingerprint,
            promptVersion = InsightPrompt.VERSION,
            styleHash = styleHash(NO_STYLE),
            provider = ai.provider.id,
            model = model,
            modelKnowledge = allowModel,
            effort = settings.aiEffort,
        )

    // D14's style is not built yet: every card uses the built-in style, the hash of no style.
    private fun styleHash(style: String): String =
        parts.hasher.sha256(style.encodeToByteArray()).joinToString("") { byte ->
            val value = byte.toInt() and BYTE_MASK
            HEX[value shr NIBBLE].toString() + HEX[value and NIBBLE_MASK]
        }

    private fun show(insight: InsightState) = sink.update { it.copy(insight = insight) }

    private fun emit(event: PipelineEvent) = sink.emit(event)

    private companion object {
        const val NO_STYLE = ""
        const val WAITS_FOR_TAP = "waits for a tap on Write (Write insights automatically)"
        const val INCOMPLETE = "incomplete"
        const val DECLINED = "declined"
        const val HEX = "0123456789abcdef"
        const val BYTE_MASK = 0xFF
        const val NIBBLE = 4
        const val NIBBLE_MASK = 0x0F
    }
}
