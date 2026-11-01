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
import app.sleevesight.core.synthesis.SelfRevision
import kotlin.time.Duration
import kotlin.time.Instant
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
 * session cited ([SessionFacts]); none, with *Don't repeat artist facts* off (#407,
 * `Settings.artistFactsOncePerSession`). Either way, it leaves out the player's release line when
 * MusicBrainz's first-release line names that album (#424). A fresh reply is checked against what
 * its prompt held, a card from the cache against the whole evidence (see `finish`); both cite the
 * whole evidence's ids. A reply that still revises itself where the card would show it
 * ([SelfRevision], #798) is cut to what follows the revision when that is usable, with no further
 * call; else it is asked for once more, a second paid call, and if that reply leaves nothing usable
 * either, the card shows the error state. A card kept from before is cut the same way, or, with
 * nothing usable, not served, as though there were none.
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
        // With when it was written, which the app checks what it shows against (#643). Against the
        // whole evidence: a card is kept only when it cites no line its prompt left out (`finish`), so
        // it reads as it read when it was written. One kept before the check that revises itself
        // (#798) is cut to its usable part, as when it was written; with none, the track is written
        // as though it had no card.
        val kept = guard.attempt("read insight") { keptCard(wanted) }
        val keptReply = kept?.let { reading(it.text, evidence, allowModel, cached = true) }
        when {
            kept != null && keptReply != null -> {
                emit(PipelineEvent.InsightCached(key))
                val sent = Prompted(evidence, evidence)
                val shown =
                    checked(
                        keptReply,
                        sent,
                        wanted,
                        cached = true,
                        usage = null,
                        complete = true,
                        writtenAt = kept.writtenAt,
                    )
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
    // all of them kept with *Don't repeat artist facts* off (#407), and less a release
    // line MusicBrainz's line restates (#424) in both cases, each line keeping its id; or from the
    // [whole] of it once the run fell back to it. The card's key keeps the whole evidence's
    // fingerprint, so a track played again finds its card, whatever the session cited. The facts an
    // insight cites are kept with the switch off too, so turning it on mid-session leaves out what
    // the session already told.
    // [revisedOnce] when a reply before this one revised itself ([SelfRevision], #798) and this call
    // asks once more; [billed] the tokens of the replies before this one in this call, discarded or
    // `NO_EVIDENCE` (#239), null for none: a card counts every reply it took.
    @Suppress("LongParameterList") // The call, and what the replies before it in this call came to.
    private suspend fun stream(
        ai: AiAccess,
        model: String,
        card: CardKey,
        control: RunControl,
        revisedOnce: Boolean = false,
        billed: Usage? = null,
    ): InsightState {
        val facts = parts.sessionFacts.citedByOthers(key)
        val once = settings.artistFactsOncePerSession
        val given = if (whole) evidence else PromptEvidence.of(evidence, nowPlaying.album, facts, parts.hasher, once)
        val sent = Prompted(evidence, given)
        val prompt = InsightPrompt.build(nowPlaying, track, sent.evidence, allowModel)
        val effort = settings.effort(ai.provider.id)
        val request = CompletionRequest(model, prompt, CompletionRequest.maxTokensFor(effort), effort)
        val text = StringBuilder()
        var frozen = false
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
                            // the marker is no prose for the card; nor once it revises itself, as
                            // the card would show it (#798): the draft shown so far stays until the
                            // check decides.
                            frozen = frozen || SelfRevision.ofPartial(text.toString()).freezes()
                            if (!GroundingCheck.mayBeNoEvidence(text.toString()) && !frozen) {
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
                is CompletionEvent.Done -> {
                    // This reply's tokens with those before it. Null: the reply revised itself, the
                    // first time (`finish`); asked once more.
                    val asked = Asked(revisedOnce, last.usage.and(billed))
                    finish(last, text.toString(), card, checkNotNull(started).elapsedNow(), control, sent, asked)
                        ?: return stream(ai, model, card, control, revisedOnce = true, billed = asked.paid)
                }
                is CompletionEvent.Error -> {
                    // How far the reply got, for the diagnostics: its length, never its text (#665).
                    emit(PipelineEvent.ModelFailed(key, last.kind, last.breakoff, text.length, started?.elapsedNow()))
                    show(InsightState.Failed(ReplyFailures.of(last)))
                }

                else -> {
                    if (ran == null) {
                        emit(PipelineEvent.ModelStopped(key))
                        show(InsightState.Stopped)
                    } else {
                        show(InsightState.Failed(ReplyFailures.INCOMPLETE))
                    }
                }
            }
        // `NO_EVIDENCE` to a prompt that left lines out, which `finish` neither shows nor reports:
        // asked once more at once, a second paid call, with them all, as `PromptEvidence` falls back
        // to them when nothing is left to narrate: an insight that repeats an artist fact is better
        // than none. A Retry tapped after that call failed or was stopped asks with them all too.
        return if (outcome == InsightState.NoEvidence && sent.leftOut.isNotEmpty()) {
            whole = true
            stream(ai, model, card, control, revisedOnce, (end as? CompletionEvent.Done)?.usage?.and(billed))
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
    // A reply that revises itself ([SelfRevision], #798) is not shown or kept either: null, for
    // `stream` to ask once more, when it is the first ([Asked.revisedOnce] false); else the error
    // state. A card counts the tokens of every reply of the call ([Asked.paid]).
    @Suppress("LongParameterList") // The reply, its card, and what its prompt was given.
    private suspend fun finish(
        done: CompletionEvent.Done,
        text: String,
        card: CardKey,
        latency: Duration,
        control: RunControl,
        sent: Prompted,
        asked: Asked,
    ): InsightState? {
        emit(PipelineEvent.ModelDone(key, done.usage, done.stopReason, latency))
        // `NO_EVIDENCE` to fewer lines than the track has is asked again (`stream`): not shown, not
        // reported as the track's outcome, not kept.
        val asksAgain =
            sent.leftOut.isNotEmpty() &&
                GroundingCheck.check(text, sent.evidence, allowModel) == InsightReply.NoEvidence
        // A reply with no text is no card: an empty one would be shown, and kept when it ended (#665).
        val blank = ReplyFailures.ofBlank(done, text)
        // What the card would show: null when the reply revised itself and nothing usable is left (#798).
        val refused = done.stopReason == StopReason.REFUSAL
        val reply = if (blank == null && !refused) reading(text, sent.evidence, allowModel, cached = false) else null
        return when {
            refused -> {
                show(InsightState.Failed(ReplyFailures.DECLINED))
            }

            blank != null -> {
                show(InsightState.Failed(blank))
            }

            asksAgain -> {
                InsightState.NoEvidence
            }

            // It revised itself with nothing usable left (#798): asked once more (null, for
            // `stream`) when it was the first; else the error state.
            reply == null -> {
                if (asked.revisedOnce) show(InsightState.Failed(ReplyFailures.SELF_REVISED)) else null
            }

            else -> {
                val complete = done.stopReason == StopReason.END
                // One time, in whole milliseconds as the cache keeps it, for the card shown and the
                // card kept, so the screen and the cache count *Keep history for* alike (#643).
                val at = Instant.fromEpochMilliseconds(time.clock.now().toEpochMilliseconds())
                val written =
                    checked(
                        reply,
                        sent,
                        card,
                        cached = false,
                        usage = asked.paid,
                        complete = complete,
                        writtenAt = at,
                    )
                val keepable =
                    sent.leftOut.isEmpty() ||
                        (written as? InsightState.Written)
                            ?.grounded
                            ?.paragraphs
                            ?.flatten()
                            ?.none { sentence -> sentence.cites.any { it in sent.leftOut } } == true
                if (complete && keepable) guard.attempt("write insight") { keep(card, text, at) }
                if (written is InsightState.Written) {
                    parts.sessionFacts.cite(key, ArtistFact.citedIn(written.grounded, sent.evidence.lines))
                    control.cardWritten(written, evidence)
                }
                show(written)
            }
        }
    }

    // [reply], checked against what was [sent] (a card from the cache against the whole evidence),
    // as a state. Written, it carries what its [card]'s key says it was written with, and when
    // ([writtenAt]).
    @Suppress("LongParameterList") // The reply, what its prompt held, its card, and how it came.
    private fun checked(
        reply: InsightReply,
        sent: Prompted,
        card: CardKey,
        cached: Boolean,
        usage: Usage?,
        complete: Boolean,
        writtenAt: Instant?,
    ): InsightState =
        when (reply) {
            InsightReply.NoEvidence -> {
                emit(PipelineEvent.NoEvidence(key))
                InsightState.NoEvidence
            }

            is InsightReply.Written -> {
                emit(PipelineEvent.Grounded(key, reply.grounded.counts, reply.grounded.withdrawn != null))
                val by = WrittenBy(card.promptVersion, card.provider, card.model, card.effort)
                InsightState.Written(reply.grounded, cached, usage, complete, sent.held, by, writtenAt)
            }
        }

    // [text] as the card would show it, checked against [evidence]: the grounding check's reply; or,
    // when that still revises itself ([SelfRevision], #798), the part after the revision
    // ([SelfRevision.salvage]), the rest withdrawn, logged as [cached] or not; null when nothing
    // usable is left (logged too).
    private fun reading(
        text: String,
        evidence: EvidenceSet,
        allowModel: Boolean,
        cached: Boolean,
    ): InsightReply? {
        val reply = GroundingCheck.check(text, evidence, allowModel)
        val grounded = (reply as? InsightReply.Written)?.grounded
        val found = grounded?.let { SelfRevision.find(it.text) } ?: return reply
        val salvaged = SelfRevision.salvage(checkNotNull(grounded), found, evidence.ids.toSet())
        emit(PipelineEvent.SelfRevised(key, found.sign, cached = cached, salvaged = salvaged != null))
        return salvaged?.let { InsightReply.Written(it) }
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
            styleHash = styleHash(parts.hasher),
            provider = ai.provider.id,
            model = model,
            modelKnowledge = allowModel,
            effort = settings.effort(ai.provider.id),
        )

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
        const val WAITS_FOR_TAP = "waits for a tap on Write (Reactive)"
    }
}

// What a reply's call came to before it ([InsightWriter]'s `finish`): whether a reply before it
// revised itself ([revisedOnce], #798), and the tokens of every reply of the call, its own too ([paid]).
private class Asked(
    val revisedOnce: Boolean,
    val paid: Usage,
)

// Whether a sign read while the reply streams stops what the card shows (#798): an aside, the first
// person or talk of the evidence; not a repeated opening, whose second occurrence is what is kept.
private fun SelfRevision?.freezes(): Boolean = this != null && this != SelfRevision.REPEATED_OPENING

// These tokens and [other]'s; these alone when there are none before them.
private fun Usage.and(other: Usage?): Usage =
    if (other == null) this else Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens)
