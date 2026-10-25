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
 * own text: the call would be paid for a `NO_EVIDENCE` or a restatement of the release. The prompt
 * leaves out artist-level lines the insight should not dwell on (#239, [PromptEvidence]): all of
 * them for a track with enough of its own, else those other tracks' insights in the listening
 * session cited ([SessionFacts]); none, with *Tell an artist's facts once per session* off (#407,
 * `Settings.artistFactsOncePerSession`). A fresh reply is checked against what its prompt held, a
 * card from the cache against the whole evidence (see `finish`); both cite the whole evidence's ids.
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

    // Whether the run fell back to the whole evidence (#239), after `NO_EVIDENCE` to a prompt that
    // left lines out: every later call of the run, a tapped Retry too, goes straight to it.
    private var whole = false

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
                // Against the whole evidence: a card is kept only when it cites no line its prompt
                // left out (`finish`), so it reads as it read when it was written.
                val shown = checked(kept, Prompted(evidence, evidence), cached = true, usage = null, complete = true)
                // Its artist facts, for the next tracks of the listening session (#239).
                (shown as? InsightState.Written)?.let {
                    parts.sessionFacts.cite(key, ArtistFact.citedIn(it.grounded, evidence.lines))
                }
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

    // A call, from the evidence less the artist-level lines the insight should not dwell on (#239),
    // each line keeping its id, or from the [whole] of it once the run fell back to it, or with
    // *Tell an artist's facts once per session* off (#407). The card's key keeps the whole evidence's
    // fingerprint, so a track played again finds its card, whatever the session cited. The facts an
    // insight cites are kept with the switch off too, so turning it on mid-session leaves out what
    // the session already told.
    private suspend fun stream(
        ai: AiAccess,
        model: String,
        card: CardKey,
        control: RunControl,
    ): InsightState {
        val given =
            if (whole || !settings.artistFactsOncePerSession) {
                evidence
            } else {
                PromptEvidence.of(evidence, parts.sessionFacts.citedByOthers(key), parts.hasher)
            }
        val sent = Prompted(evidence, given)
        val prompt = InsightPrompt.build(nowPlaying, track, sent.evidence, allowModel)
        val request = CompletionRequest(model, prompt, effort = settings.effort(ai.provider.id))
        val text = StringBuilder()
        var end: CompletionEvent? = null
        var started: TimeMark? = null
        // The request goes out only if no Stop came first. The stream is read to its end, as the
        // transport closes it (the CLI's recorder keeps a reply only then); a Stop meanwhile
        // cancels it.
        val ran =
            control.unlessStopped {
                emit(PipelineEvent.ModelCall(key, ai.provider.id, model, sent.leftOut))
                show(InsightState.Writing("", sent.held))
                started = time.timeSource.markNow()
                ai.provider.stream(request, ai.key).collect { event ->
                    when (event) {
                        is CompletionEvent.Delta -> {
                            text.append(event.text)
                            // Not while it may still be `NO_EVIDENCE`, or is, as the check reads it:
                            // the marker is no prose for the card.
                            if (!GroundingCheck.mayBeNoEvidence(text.toString())) {
                                show(InsightState.Writing(text.toString(), sent.held))
                            }
                        }

                        is CompletionEvent.Done, is CompletionEvent.Error -> {
                            end = event
                        }
                    }
                }
            }
        // Once the reply's end has come (Done or Error), it is the outcome, whatever a Stop did
        // after it while the transport was still open: what was paid for is kept.
        val outcome =
            when (val last = end) {
                is CompletionEvent.Done ->
                    finish(last, text.toString(), card, checkNotNull(started).elapsedNow(), control, sent)
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
        // `NO_EVIDENCE` to a prompt that left lines out, which `finish` neither shows nor reports:
        // asked once more at once, a second paid call, with them all, as `PromptEvidence` falls back
        // to them when nothing is left to narrate: an insight that repeats an artist fact is better
        // than none. A Retry tapped after that call failed or was stopped asks with them all too.
        return if (outcome == InsightState.NoEvidence && sent.leftOut.isNotEmpty()) {
            whole = true
            stream(ai, model, card, control)
        } else {
            outcome
        }
    }

    // The reply, checked against what its prompt was [sent] (#239): a citation of a line left out
    // of it is `unknown`, since the model never saw that line, though the track has it. Such a reply
    // is shown and not kept; `NO_EVIDENCE` to a prompt that left lines out is not even shown (`stream`
    // asks again with them all): every card in the cache cites only lines its prompt held, and reads the
    // same when it is served again and checked against the whole evidence, whatever a later session
    // leaves out. A reply not kept is written again, a paid call, the next time the track plays.
    @Suppress("LongParameterList") // The reply, its card, and what its prompt was given.
    private suspend fun finish(
        done: CompletionEvent.Done,
        text: String,
        card: CardKey,
        latency: Duration,
        control: RunControl,
        sent: Prompted,
    ): InsightState {
        emit(PipelineEvent.ModelDone(key, done.usage, done.stopReason, latency))
        // `NO_EVIDENCE` to fewer lines than the track has is asked again (`stream`): not shown, not
        // reported as the track's outcome, not kept.
        val asksAgain =
            sent.leftOut.isNotEmpty() &&
                GroundingCheck.check(text, sent.evidence, allowModel) == InsightReply.NoEvidence
        return when {
            done.stopReason == StopReason.REFUSAL -> {
                show(InsightState.Failed(DECLINED))
            }

            asksAgain -> {
                InsightState.NoEvidence
            }

            else -> {
                val complete = done.stopReason == StopReason.END
                val written = checked(text, sent, cached = false, usage = done.usage, complete = complete)
                val keepable =
                    sent.leftOut.isEmpty() ||
                        (written as? InsightState.Written)
                            ?.grounded
                            ?.paragraphs
                            ?.flatten()
                            ?.none { sentence -> sentence.cites.any { it in sent.leftOut } } == true
                if (complete && keepable) guard.attempt("write insight") { keep(card, text) }
                if (written is InsightState.Written) {
                    parts.sessionFacts.cite(key, ArtistFact.citedIn(written.grounded, sent.evidence.lines))
                    control.cardWritten(written, evidence)
                }
                show(written)
            }
        }
    }

    // [text] checked against what was [sent]; a card from the cache against the whole evidence.
    private fun checked(
        text: String,
        sent: Prompted,
        cached: Boolean,
        usage: Usage?,
        complete: Boolean,
    ): InsightState =
        when (val reply = GroundingCheck.check(text, sent.evidence, allowModel)) {
            InsightReply.NoEvidence -> {
                emit(PipelineEvent.NoEvidence(key))
                InsightState.NoEvidence
            }

            is InsightReply.Written -> {
                emit(PipelineEvent.Grounded(key, reply.grounded.counts))
                InsightState.Written(reply.grounded, cached, usage, complete, sent.held)
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

    // What a prompt was given of the [whole] evidence: [evidence], the same lines or fewer, each with its id.
    private class Prompted(
        whole: EvidenceSet,
        val evidence: EvidenceSet,
    ) {
        /** The ids of the lines left out, in the whole evidence's order. */
        val leftOut: List<String> = if (evidence === whole) emptyList() else whole.ids - evidence.ids.toSet()

        /** The ids it held, when it left some out; null when it held them all. */
        val held: Set<String>? = evidence.ids.toSet().takeIf { leftOut.isNotEmpty() }
    }

    private companion object {
        const val NO_STYLE = ""
        const val WAITS_FOR_TAP = "waits for a tap on Write (Reactive)"
        const val INCOMPLETE = "incomplete"
        const val DECLINED = "declined"
        const val HEX = "0123456789abcdef"
        const val BYTE_MASK = 0xFF
        const val NIBBLE = 4
        const val NIBBLE_MASK = 0x0F
    }
}
