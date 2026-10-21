package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.evidence.EvidenceSet
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightPrompt
import app.sleevesight.core.synthesis.InsightReply
import kotlin.time.Duration
import kotlin.time.TimeMark

/**
 * The home insight for one track, once its [evidence] is in: from the synthesis cache when it has
 * this exact card (R2), else written by the model when asked, and always checked
 * ([GroundingCheck]) before it is shown. When the evidence has nothing to narrate
 * ([EvidenceSet.nothingToNarrate]: no line, or only the player's own release, unless model
 * knowledge is on and the report names the artists), no call is made and the card shows the app's
 * own text: the call would be paid for a `NO_EVIDENCE` or a restatement of the release.
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
     * Write; else leaves it offered. A model call reports its end to [control], however it ends;
     * one that fails or is stopped is made again only when [control] says a tap asked for it.
     */
    suspend fun write(control: RunControl) {
        val ai = parts.ai
        // The switched-on provider's model, the one whose client the parts carry.
        val model = ai?.let { settings.model(it.provider.id) }
        when {
            !settings.writeInsights || ai == null || model == null -> {
                control.decidedWithoutCall()
                show(InsightState.Idle(canWrite = false))
            }

            evidence.nothingToNarrate(allowModel, artistsKnown = track.artists.isNotEmpty()) -> {
                control.nothingToNarrate(settings)
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
                calls(ai, model, wanted, control)
            }

            else -> {
                show(InsightState.Idle(canWrite = true))
                emit(PipelineEvent.InsightWaits(key, WAITS_FOR_TAP))
                if (control.waitsForTap) {
                    control.awaitTap()
                    calls(ai, model, wanted, control)
                }
            }
        }
    }

    // A call, and another each time one that failed or was stopped is asked for again by a tap.
    // Each reports its end, however it ends.
    private suspend fun calls(
        ai: AiAccess,
        model: String,
        card: CardKey,
        control: RunControl,
    ) {
        do {
            val shown =
                try {
                    stream(ai, model, card, control)
                } finally {
                    control.ended(RunStep.MODEL_CALL)
                }
            val again = (shown is InsightState.Failed || shown is InsightState.Stopped) && control.awaitRetry()
        } while (again)
    }

    private suspend fun stream(
        ai: AiAccess,
        model: String,
        card: CardKey,
        control: RunControl,
    ): InsightState {
        val prompt = InsightPrompt.build(nowPlaying, track, evidence, allowModel)
        val request = CompletionRequest(model, prompt, effort = settings.effort(ai.provider.id))
        val text = StringBuilder()
        var end: CompletionEvent? = null
        var started: TimeMark? = null
        // The request goes out only if no Stop came first. The stream is read to its end, as the
        // transport closes it (the CLI's recorder keeps a reply only then); a Stop meanwhile
        // cancels it.
        val ran =
            control.unlessStopped {
                emit(PipelineEvent.ModelCall(key, ai.provider.id, model))
                show(InsightState.Writing(""))
                started = time.timeSource.markNow()
                ai.provider.stream(request, ai.key).collect { event ->
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
            }
        // Once the reply's end has come (Done or Error), it is the outcome, whatever a Stop did
        // after it while the transport was still open: what was paid for is kept.
        return when (val last = end) {
            is CompletionEvent.Done -> finish(last, text.toString(), card, checkNotNull(started).elapsedNow(), control)
            is CompletionEvent.Error -> {
                emit(PipelineEvent.ModelFailed(key, last.kind))
                show(InsightState.Failed(last.kind.name.lowercase()))
            }

            else -> {
                if (ran == null) {
                    emit(PipelineEvent.ModelStopped(key))
                    show(InsightState.Stopped)
                } else {
                    show(InsightState.Failed(INCOMPLETE))
                }
            }
        }
    }

    private suspend fun finish(
        done: CompletionEvent.Done,
        text: String,
        card: CardKey,
        latency: Duration,
        control: RunControl,
    ): InsightState {
        emit(PipelineEvent.ModelDone(key, done.usage, done.stopReason, latency))
        if (done.stopReason == StopReason.REFUSAL) return show(InsightState.Failed(DECLINED))
        val complete = done.stopReason == StopReason.END
        if (complete) guard.attempt("write insight") { keep(card, text) }
        val written = checked(text, cached = false, usage = done.usage, complete = complete)
        if (written is InsightState.Written) control.cardWritten(written, evidence)
        return show(written)
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
            effort = settings.effort(ai.provider.id),
        )

    // D14's style is not built yet: every card uses the built-in style, the hash of no style.
    private fun styleHash(style: String): String =
        parts.hasher.sha256(style.encodeToByteArray()).joinToString("") { byte ->
            val value = byte.toInt() and BYTE_MASK
            HEX[value shr NIBBLE].toString() + HEX[value and NIBBLE_MASK]
        }

    // Shows [insight], and returns it.
    private fun show(insight: InsightState): InsightState {
        sink.update { it.copy(insight = insight) }
        return insight
    }

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
