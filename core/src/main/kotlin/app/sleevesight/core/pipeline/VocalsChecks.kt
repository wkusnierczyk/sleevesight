package app.sleevesight.core.pipeline

import app.sleevesight.core.ai.CompletionEvent
import app.sleevesight.core.ai.CompletionRequest
import app.sleevesight.core.ai.StopReason
import app.sleevesight.core.evidence.Hasher
import app.sleevesight.core.genres.VocalsAnswer
import app.sleevesight.core.settings.AiWebSearch
import app.sleevesight.core.settings.Settings
import app.sleevesight.core.sources.WebPage
import app.sleevesight.core.sources.WebSearcher
import app.sleevesight.core.synthesis.AiSearchAnswers
import app.sleevesight.core.synthesis.CardKind
import app.sleevesight.core.synthesis.VocalsPrompt
import app.sleevesight.core.synthesis.sameAddress
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/** The check of a track's vocals (#998), as the genre's panel works with it. */
sealed interface VocalsState {
    /** The search or the call is under way: "Checking whether this track has vocals…". */
    data object Checking : VocalsState

    /**
     * The check's [answer], as read and grounded: [page], the page it rests on, one its searches
     * found (none for [VocalsAnswer.UNKNOWN]); [model], the model's id, as the key has it.
     */
    data class Checked(
        val answer: VocalsAnswer,
        val model: String,
        val page: WebPage? = null,
    ) : VocalsState

    /**
     * The check failed, for [reason] (a `ReplyFailures` label, [AiSearches.WEB_SEARCH_FAILED], or
     * [VocalsChecks.MALFORMED]), or was not made, its gate closed before a paid step
     * ([VocalsChecks.REFUSED]); not kept.
     */
    data class Failed(
        val reason: String,
    ) : VocalsState
}

/** A check kept in the cache: its [state] as read now, and when it was written ([writtenAt]), if the cache says. */
data class KeptCheck(
    val state: VocalsState.Checked,
    val writtenAt: Instant?,
)

/**
 * The check of a track's vocals (#998; `design/design.md`, *Genre labels*): when its Instrumental is
 * in doubt (`TrackGenres.wantsVocalsCheck`), one web search for the track with its vocals in view and
 * one call to the AI service ([ai]) reading the pages, as *AI web search* asks (`WebQuestions`):
 * *Once*, the app's query ([VocalsPrompt.query]: the title, the artists and words such as
 * "instrumental", "vocals" and "lyrics"), one search and one call; *Up to 3 times*, the model writes
 * up to three searches, at most four calls. Never from the model's own knowledge: with *AI web search*
 * off, or no web search engine ([web]), there is no check ([key] null). The model answers
 * `instrumental`, `vocals` or `unknown` with the address of the page that says so ([VocalsPrompt]);
 * an answer whose page no search found is read as `unknown`, as *Ask AI*'s are grounded.
 *
 * It is kept in the synthesis cache ([cache]) as a card of [CardKind.VOCALS], for as long as a
 * reason: keyed by the track, its length and the album the player names (the prompt's only data),
 * the prompt version, the AI service, its model and effort ([key]), so a replay asks nothing. An
 * answer read is kept with the page it rests on (none for `unknown`) and the time it is shown with; a
 * failure is not, nor a check whose gate closed before a paid step ([VocalsChecks.REFUSED]).
 */
class VocalsChecks(
    private val ai: AiAccess?,
    private val cache: PipelineCache,
    private val hasher: Hasher,
    private val clock: Clock = Clock.System,
    private val web: WebSearcher? = null,
) {
    /**
     * The key of [track]'s check under [settings], or null when none may be asked: *Show insights*
     * off or the evidence not in ([GenreReasons.offered]), no AI service with a model, or *AI web
     * search* not used (off, set so or with Exa web search off, or no engine to run it).
     */
    fun key(
        track: TrackState,
        settings: Settings,
    ): CardKey? {
        val access =
            ai?.takeIf {
                GenreReasons.offered(track, settings) &&
                    WebQuestions(it, web).used(settings.aiWebSearchUsed) != AiWebSearch.OFF
            }
        val model = access?.let { settings.model(it.provider.id) }
        if (access == null || model == null) return null
        return CardKey(
            kind = CardKind.VOCALS,
            entity = CardEntity.Track(track.normalized, track.nowPlaying.duration, subject = subject(track)),
            evidenceFingerprint = AiSearches.NO_FINGERPRINT,
            promptVersion = VocalsPrompt.VERSION,
            styleHash = styleHash(hasher),
            provider = access.provider.id,
            model = model,
            modelKnowledge = false,
            effort = settings.effort(access.provider.id),
        )
    }

    /** The check kept for [key], read and grounded again; null when none is kept. */
    suspend fun kept(key: CardKey): KeptCheck? {
        val card = attempt { cache.keptCard(key) }
        val kept = card?.let { KeptVocalsText.of(it.text) }
        return kept?.let { read(it.reply, key, it.pages.map(KeptPage::page)) }?.let { KeptCheck(it, card?.writtenAt) }
    }

    /**
     * Asks [key]'s check for [track] as [asking] says (*Once* when it says *Off* since the key was
     * made), each state to [show] with when it came; returns the last. Each search and call is counted
     * to [counted] as it is made, and made only while [mayPay] says so, asked right before it. An
     * answer read is kept, and shown, whatever cancels the call then; a failure is shown, and not kept.
     */
    @Suppress("LongParameterList") // The check, its track, how it searches, its cost and gate, and its states.
    suspend fun ask(
        key: CardKey,
        track: TrackState,
        asking: AiSearches.Asking = AiSearches.Asking(),
        counted: (QuestionCost) -> Unit = {},
        mayPay: () -> Boolean = { true },
        show: (VocalsState, Instant) -> Unit,
    ): VocalsState {
        val access = checkNotNull(ai) { "no AI service" }
        show(VocalsState.Checking, now())
        val base = CompletionRequest(key.model, "", AiSearches.maxTokensFor(key.effort), key.effort)
        val questions = WebQuestions(access, web)
        val search = questions.used(asking.search).takeUnless { it == AiWebSearch.OFF } ?: AiWebSearch.ONCE
        val asked = questions.ask(question(track), search, asking.limit, base, counted, mayPay)
        val pages = asked.pages.orEmpty()
        val end = asked.end
        val done = (end as? CompletionEvent.Done)?.takeIf { it.stopReason == StopReason.END }
        val checked =
            when {
                asked.refused || asked.searchFailed -> null
                // No page found: no call, and nothing says either.
                end == null -> VocalsState.Checked(VocalsAnswer.UNKNOWN, key.model)
                else -> done?.let { read(asked.text, key, pages) }
            }
        val state =
            checked ?: VocalsState.Failed(
                when {
                    asked.refused -> REFUSED
                    asked.searchFailed -> AiSearches.WEB_SEARCH_FAILED
                    else -> failure(end, asked.text)
                },
            )
        return withContext(NonCancellable) {
            val at = now()
            if (checked != null) {
                // The page it rests on alone, which grounds it again as it is read.
                val text =
                    KeptVocalsText(
                        asked.text.ifEmpty { "{}" },
                        listOfNotNull(checked.page).map(KeptPage::of),
                    ).text()
                attempt { cache.keep(key, text, at) }
            }
            show(state, at)
            state
        }
    }

    // [track]'s question, from the web only: the track as the player and the normalizer give it.
    private fun question(track: TrackState): WebQuestion =
        object : WebQuestion {
            override val knowledge: String
                get() = VocalsPrompt.once(track.nowPlaying, track.normalized, emptyList())
            override val query: String get() = VocalsPrompt.query(track.normalized)
            override val subjectNames: List<String> get() = track.normalized.subjectNames

            override fun once(pages: List<WebPage>): String =
                VocalsPrompt.once(track.nowPlaying, track.normalized, pages)

            override fun agent(limit: Int): String = VocalsPrompt.agent(track.nowPlaying, track.normalized, limit)
        }

    // The answer in [reply], grounded in [pages]: an answer with no page among them, or none, is
    // `unknown`; null when the reply is not the JSON object asked for.
    private fun read(
        reply: String,
        key: CardKey,
        pages: List<WebPage>,
    ): VocalsState.Checked? {
        val answer = AiSearchAnswers.objectIn(reply) ?: return null
        val said = VocalsAnswer.of(stringOf(answer["answer"])) ?: VocalsAnswer.UNKNOWN
        val source = stringOf(answer["source"])
        val page = source?.let { s -> pages.firstOrNull { sameAddress(it.url, s) } }
        return when {
            said == VocalsAnswer.UNKNOWN || page == null -> VocalsState.Checked(VocalsAnswer.UNKNOWN, key.model)
            else -> VocalsState.Checked(said, key.model, page)
        }
    }

    private fun stringOf(element: JsonElement?): String? = (element as? JsonPrimitive)?.takeIf { it.isString }?.content

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
        /** Why a check failed when its reply was not the JSON object asked for. */
        const val MALFORMED: String = "malformed"

        /** Why a check was not made: its gate closed before a search or a call (`WebReply.refused`). */
        const val REFUSED: String = "refused"

        // What [track]'s check is about besides the track: the album the player names, as the prompt
        // gives it, case and spacing aside; empty for none.
        private fun subject(track: TrackState): String =
            track.nowPlaying.album
                ?.trim()
                ?.lowercase()
                .orEmpty()

        // Why a reply that is no answer failed: the service's error, a refusal, no text, a reply cut
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

/**
 * What a check keeps in the synthesis cache (#998): its [reply], and the page it rests on ([pages]:
 * one, or none for `unknown`), which grounds it again as it is read. Marked [MARK].
 */
@Serializable
internal class KeptVocalsText(
    val reply: String,
    val pages: List<KeptPage>,
    @SerialName(MARK) val mark: Int = 1,
) {
    fun text(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val MARK: String = "sleevesight_vocals"

        private val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /** The kept check in [text], or null when it is not one. */
        fun of(text: String): KeptVocalsText? =
            if (!text.contains("\"$MARK\"")) {
                null
            } else {
                @Suppress("SwallowedException") // Not one: nothing kept.
                try {
                    JSON.decodeFromString(serializer(), text)
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
    }
}
