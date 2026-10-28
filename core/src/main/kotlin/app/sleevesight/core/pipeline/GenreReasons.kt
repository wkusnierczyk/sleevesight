package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.ai.Usage
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.genres.LabelSource
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.GenreReasonPrompt
import app.sleevesight.core.synthesis.GroundingCheck
import app.sleevesight.core.synthesis.InsightReply
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/** A reason kept in the cache: its [state] as checked, and when it was written ([writtenAt]), if the cache says. */
data class KeptReason(
    val state: InsightState,
    val writtenAt: Instant?,
)

/**
 * *Why is this …?* in a genre's panel (#523; `design/design.md`, *Genre labels*): why the track
 * carries a genre label, written on a tap from its evidence by the AI service ([ai]), one call,
 * through the insight's grounding check, and kept in the synthesis cache ([cache]) as a card of
 * [CardKind.GENRE], keyed like the insight ([key]: the track, the evidence's fingerprint, the prompt
 * version, the AI service, its model and effort, the model-knowledge switch) and the genre
 * ([CardKey.subject]). A reason once written is found again with no call ([kept]) for as long as its
 * key holds: a settings change that would offer the insight's *Rewrite insight* (another service,
 * model or effort, new evidence) misses it, and *Why is this …?* is offered again.
 *
 * Its states are the insight's ([InsightState]): [InsightState.Writing] while the reply comes,
 * [InsightState.Written] once checked, [InsightState.NoEvidence] when the evidence has nothing to
 * narrate (no call) or the model replies `NO_EVIDENCE`, [InsightState.Failed] when the call fails or
 * is declined. Not offered ([key] null) for a genre only MusicBrainz's tags give (D12: they never
 * reach a prompt), with *Show insights* off, with no AI service ready, or before the evidence is in.
 *
 * A cache that throws is a miss, and a write that fails keeps nothing; neither stops the reason.
 * Each state written comes with its time by [clock]; a reply kept is kept with the time it is shown
 * with, so the screen and the cache count *Keep history for* from the same time, as the insight's
 * (#643).
 */
class GenreReasons(
    private val ai: AiAccess?,
    private val cache: PipelineCache,
    private val hasher: Hasher,
    private val clock: Clock = Clock.System,
) {
    /**
     * The key of [genre]'s reason for [track] under [settings] ([GenreLabel.identity]), or null when
     * none may be written: see the class.
     */
    fun key(
        track: TrackState,
        genre: String,
        settings: Settings,
    ): CardKey? {
        val access = ai?.takeIf { settings.writeInsights && track.gathered && prompted(track, genre) }
        val model = access?.let { settings.model(it.provider.id) }
        val evidence = track.evidence
        if (access == null || model == null || evidence == null) return null
        return CardKey(
            kind = CardKind.GENRE,
            track = track.normalized,
            duration = track.nowPlaying.duration,
            evidenceFingerprint = evidence.fingerprint,
            promptVersion = GenreReasonPrompt.VERSION,
            styleHash = styleHash(hasher),
            provider = access.provider.id,
            model = model,
            modelKnowledge = settings.isEnabled(SourceId.MODEL),
            effort = settings.effort(access.provider.id),
            subject = genre,
        )
    }

    /**
     * The reason kept for [key], checked against [track]'s evidence, with when it was written where
     * the cache says; null when none is kept.
     */
    suspend fun kept(
        key: CardKey,
        track: TrackState,
    ): KeptReason? {
        val card = attempt { cache.keptCard(key) } ?: return null
        return KeptReason(checked(card.text, key, track, cached = true, usage = null, complete = true), card.writtenAt)
    }

    /**
     * Writes [key]'s reason for [track], one call, each state as it comes to [show] with when it
     * came (by the clock, in whole milliseconds as the cache keeps it); returns the last. The genre's
     * label is [label] (`GenreLabel.label`, as the chip shows it). A complete reply is kept in the
     * cache, `NO_EVIDENCE` too, so a tap on the same reason asks nothing again, with the one time it
     * is shown with (`PipelineCache.keep` with `writtenAt`).
     */
    suspend fun write(
        key: CardKey,
        track: TrackState,
        label: String,
        show: (InsightState, Instant) -> Unit,
    ): InsightState {
        val access = checkNotNull(ai) { "no AI service" }
        val evidence = checkNotNull(track.evidence) { "no evidence" }
        if (evidence.nothingToNarrate(key.modelKnowledge, artistsKnown = track.normalized.artists.isNotEmpty())) {
            return InsightState.NoEvidence.also { show(it, now()) }
        }
        val prompt = GenreReasonPrompt.build(track.nowPlaying, track.normalized, evidence, label, key.modelKnowledge)
        val text = StringBuilder()
        var end: CompletionEvent? = null
        show(InsightState.Writing(""), now())
        val request = CompletionRequest(key.model, prompt, CompletionRequest.maxTokensFor(key.effort), key.effort)
        access.provider.stream(request, access.key).collect { event ->
            when (event) {
                is CompletionEvent.Delta -> {
                    text.append(event.text)
                    if (!GroundingCheck.mayBeNoEvidence(text.toString())) {
                        show(InsightState.Writing(text.toString()), now())
                    }
                }

                is CompletionEvent.Done, is CompletionEvent.Error -> {
                    end = event
                }
            }
        }
        // One time for the last state shown and the reply kept.
        val at = now()
        val shown =
            when (val last = end) {
                is CompletionEvent.Done -> {
                    val blank = ReplyFailures.ofBlank(last, text.toString())
                    when {
                        last.stopReason == StopReason.REFUSAL -> {
                            InsightState.Failed(DECLINED)
                        }

                        // No text is no reason to show, nor to keep (#665).
                        blank != null -> {
                            InsightState.Failed(blank)
                        }

                        else -> {
                            val complete = last.stopReason == StopReason.END
                            if (complete) attempt { cache.keep(key, text.toString(), at) }
                            checked(text.toString(), key, track, cached = false, usage = last.usage, complete)
                        }
                    }
                }

                is CompletionEvent.Error -> {
                    InsightState.Failed(ReplyFailures.of(last))
                }

                else -> {
                    InsightState.Failed(INCOMPLETE)
                }
            }
        return shown.also { show(it, at) }
    }

    // The clock's time, in whole milliseconds, as the cache keeps it.
    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())

    @Suppress("LongParameterList") // The reply, its key, its track, and how it came.
    private fun checked(
        text: String,
        key: CardKey,
        track: TrackState,
        cached: Boolean,
        usage: Usage?,
        complete: Boolean,
    ): InsightState {
        val evidence = track.evidence ?: return InsightState.NoEvidence
        return when (val reply = GroundingCheck.check(text, evidence, key.modelKnowledge)) {
            InsightReply.NoEvidence -> {
                InsightState.NoEvidence
            }

            is InsightReply.Written -> {
                val by = WrittenBy(key.promptVersion, key.provider, key.model, key.effort)
                InsightState.Written(reply.grounded, cached, usage, complete, writtenBy = by)
            }
        }
    }

    // [call], or null when it throws anything but cancellation: a cache that fails is a miss.
    private suspend fun <T> attempt(call: suspend () -> T): T? =
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            null
        }

    companion object {
        /** Why a reason failed when the model declined. */
        const val DECLINED: String = ReplyFailures.DECLINED

        /** Why a reason failed when its reply never ended. */
        const val INCOMPLETE: String = ReplyFailures.INCOMPLETE

        /**
         * Whether [track]'s genre [genre] may reach a prompt: a source other than MusicBrainz gives it,
         * on some row (D12: MusicBrainz's tags, CC BY-NC-SA 3.0, never reach a prompt).
         */
        fun prompted(
            track: TrackState,
            genre: String,
        ): Boolean =
            track.genres.rows
                .flatMap { it.labels }
                .any { label ->
                    label.genre.identity == genre && label.credits.any { it.source != LabelSource.MUSICBRAINZ }
                }
    }
}
