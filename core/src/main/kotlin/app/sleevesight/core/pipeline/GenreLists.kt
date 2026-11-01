package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.genres.GenreLabel
import app.sleevesight.core.genres.GenreLevel
import app.sleevesight.core.genres.GenreVocabulary
import app.sleevesight.core.model.SourceId
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.GenreListMode
import app.sleevesight.core.synthesis.GenreListPrompt
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/** The AI's genre list for a track (#762), as the genre's panel works with it. */
sealed interface GenreListState {
    /** The call is under way: "Looking for an explanation…". */
    data object Asking : GenreListState

    /**
     * The list, as checked: [genres], labels of the app's genre list, in the reply's order, at most
     * five; none when the reply named none of them. [model] is the model's id, as the key has it.
     */
    data class Listed(
        val genres: List<GenreLabel>,
        val model: String,
    ) : GenreListState

    /** The call failed, for [reason] (a `ReplyFailures` label, or [GenreLists.MALFORMED]); not kept. */
    data class Failed(
        val reason: String,
    ) : GenreListState
}

/** A list kept in the cache: its [state] as checked now, and when it was written ([writtenAt]), if the cache says. */
data class KeptList(
    val state: GenreListState.Listed,
    val writtenAt: Instant?,
)

/**
 * The AI's genre list for a track (#762; `design/design.md`, *Genre labels*): asked on a tap on *Why
 * is this …?* for a genre only MusicBrainz's tags give, which never reaches a prompt (D12), one call
 * to the AI service ([ai]) that names no genre ([GenreListPrompt]); a genre on the list then has its
 * reason written as any other ([GenreReasons]), and the genres on it join the track's row
 * (`TrackGenres.withAi`). The reply is read as *Search with AI*'s genres are ([AiSearchAnswers.objectIn],
 * [GenreVocabulary.labelsFor]: only names of the app's genre list, [vocabulary], at most five).
 *
 * It is kept in the synthesis cache ([cache]) as a card of [CardKind.GENRE_LIST], for as long as a
 * reason: keyed as the reason is ([key]: the track, the evidence's fingerprint, the prompt version,
 * the AI service, its model and effort, the model-knowledge switch), its mode's key as the subject,
 * so a second tap, or another genre's, asks nothing again. A reply read, with genres or none, is
 * kept with the time it is shown with; a failure is not.
 *
 * It runs in a mode the settings allow ([mode]): from the model's own knowledge with *AI service
 * knowledge* on; with none ([mode] null), it cannot run, and the panel names the switches that would
 * let it ([MODES]). Web search is #801's mode.
 */
class GenreLists(
    private val ai: AiAccess?,
    private val cache: PipelineCache,
    private val hasher: Hasher,
    private val vocabulary: GenreVocabulary,
    private val clock: Clock = Clock.System,
) {
    /**
     * The key of [track]'s genre list under [settings], or null when none may be asked: no AI service
     * ready (as [GenreReasons.key] asks), or no [mode].
     */
    fun key(
        track: TrackState,
        settings: Settings,
    ): CardKey? {
        val mode = mode(settings)
        val access = ai?.takeIf { mode != null && GenreReasons.offered(track, settings) }
        val model = access?.let { settings.model(it.provider.id) }
        val evidence = track.evidence
        // No access without a mode.
        if (access == null || model == null || evidence == null) return null
        return CardKey(
            kind = CardKind.GENRE_LIST,
            track = track.normalized,
            duration = track.nowPlaying.duration,
            evidenceFingerprint = evidence.fingerprint,
            promptVersion = GenreListPrompt.VERSION,
            styleHash = styleHash(hasher),
            provider = access.provider.id,
            model = model,
            modelKnowledge = settings.isEnabled(SourceId.MODEL),
            effort = settings.effort(access.provider.id),
            subject = mode?.key,
        )
    }

    /** The list kept for [key], read and checked again; null when none is kept. */
    suspend fun kept(key: CardKey): KeptList? {
        val card = attempt { cache.keptCard(key) } ?: return null
        return read(card.text, key)?.let { KeptList(it, card.writtenAt) }
    }

    /**
     * Asks [key]'s list for [track], one call: each state as it comes to [show] with when it came;
     * returns the last. The list read is kept, and shown, whatever cancels the call then; a failure
     * is shown, and not kept.
     */
    suspend fun ask(
        key: CardKey,
        track: TrackState,
        show: (GenreListState, Instant) -> Unit,
    ): GenreListState {
        val access = checkNotNull(ai) { "no AI service" }
        val mode = checkNotNull(GenreListMode.entries.firstOrNull { it.key == key.subject }) { "no mode" }
        show(GenreListState.Asking, now())
        val prompt = GenreListPrompt.build(track.nowPlaying, track.normalized, mode)
        val request = CompletionRequest(key.model, prompt, AiSearches.maxTokensFor(key.effort), key.effort)
        val text = StringBuilder()
        var end: CompletionEvent? = null
        access.provider.stream(request, access.key).collect { event ->
            when (event) {
                is CompletionEvent.Delta -> text.append(event.text)
                is CompletionEvent.Done, is CompletionEvent.Error -> end = event
            }
        }
        val reply = text.toString()
        val done = (end as? CompletionEvent.Done)?.takeIf { it.stopReason == StopReason.END }
        val listed = done?.let { read(reply, key) }
        val state = listed ?: GenreListState.Failed(failure(end, reply))
        return withContext(NonCancellable) {
            val at = now()
            if (listed != null) attempt { cache.keep(key, reply, at) }
            show(state, at)
            state
        }
    }

    // The list in [reply], checked, by [key]'s model; null when it is not the JSON object asked for.
    private fun read(
        reply: String,
        key: CardKey,
    ): GenreListState.Listed? {
        val answer = AiSearchAnswers.objectIn(reply) ?: return null
        val names =
            (answer["genres"] as? JsonArray).orEmpty().mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
        return GenreListState.Listed(vocabulary.labelsFor(names, level = GenreLevel.TRACK), key.model)
    }

    // The clock's time, in whole milliseconds, as the cache keeps it.
    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())

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
        /** Why a list failed when its reply was not the JSON object asked for. */
        const val MALFORMED: String = "malformed"

        /**
         * Every mode a list can be asked in, in the order the panel names them when none is on (#762:
         * "Enable AI service knowledge."): what can be switched on to let it run.
         */
        val MODES: List<GenreListMode> = GenreListMode.entries

        /** The mode [settings] let a list be asked in: the model's own knowledge, when it is on; else none. */
        fun mode(settings: Settings): GenreListMode? =
            GenreListMode.KNOWLEDGE.takeIf { settings.isEnabled(SourceId.MODEL) }

        // Why a reply that is no list failed: the service's error, a refusal, no text, a reply cut
        // off, or one that is not the JSON object asked for.
        private fun failure(
            end: CompletionEvent?,
            reply: String,
        ): String =
            when {
                end is CompletionEvent.Error -> ReplyFailures.of(end)
                end !is CompletionEvent.Done -> ReplyFailures.INCOMPLETE
                end.stopReason == StopReason.REFUSAL -> ReplyFailures.DECLINED
                else ->
                    ReplyFailures.ofBlank(end, reply)
                        ?: if (end.stopReason == StopReason.END) MALFORMED else ReplyFailures.INCOMPLETE
            }
    }
}
